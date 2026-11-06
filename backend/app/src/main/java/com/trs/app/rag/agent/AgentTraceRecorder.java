package com.trs.app.rag.agent;

import com.trs.app.rag.*;
import com.trs.app.rag.query.QueryRequest;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, ordered asynchronous trace writes; capture failures never replace answer results. @author czhcc */
@Service
@RequiredArgsConstructor
public class AgentTraceRecorder {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final TraceProtection protection;
  private final org.springframework.transaction.PlatformTransactionManager transactions;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon(true).name("agent-trace-writer").factory(), new ThreadPoolExecutor.AbortPolicy());
  private final ConcurrentMap<UUID, State> states = new ConcurrentHashMap<>();
  private static class State { final long started = System.nanoTime(); final AtomicInteger sequence = new AtomicInteger(); int bytes; volatile boolean incomplete, closed; }
  /** Identifies a step without storing request bodies in ordinary logs. @author czhcc */
  public record Span(UUID id, UUID trace, long started) {}
  /** Starts only after a private conversation turn exists. @author czhcc */
  public UUID begin(AgentRunContext run) {
    UUID id = begin(run.getSnapshot().getKnowledgeBaseId(), run.getRequest(), run.getActor());
    generation(id, run.getSnapshot().getGenerationId()); return id;
  }
  /** Creates the turn trace before snapshot/model capture so initialization failures remain visible. @author czhcc */
  public UUID begin(UUID kb, QueryRequest request, String actor) {
    if (request.getConversationId() == null || states.size() >= 64) return null;
    UUID id = UUID.randomUUID();
    try {
      owned(kb, request.getConversationId(), request.getRequestId(), actor);
      var tx = new org.springframework.transaction.support.TransactionTemplate(transactions); tx.setTimeout(3);
      tx.executeWithoutResult(ignored -> jdbc.update("INSERT INTO rag_agent_trace(id,knowledge_base_id,conversation_id,turn_id) VALUES (?,?,?,?)", id,
          kb, request.getConversationId(), request.getRequestId()));
      states.put(id, new State()); return id;
    } catch (RuntimeException unavailable) { return null; }
  }
  /** Adds the captured generation without retaining a foreign key to removable graph rows. @author czhcc */
  public void generation(UUID trace, UUID generation) { submit(trace, () -> jdbc.update("UPDATE rag_agent_trace SET generation_id=? WHERE id=?", generation, trace)); }
  private void submit(UUID trace, Runnable work) {
    if (trace == null) return;
    State state = states.get(trace); if (state == null) return;
    try { writer.execute(() -> { try {
      var tx = new org.springframework.transaction.support.TransactionTemplate(transactions); tx.setTimeout(3); tx.executeWithoutResult(ignored -> work.run());
    } catch (RuntimeException unavailable) {
      state.incomplete = true;
      try { jdbc.update("UPDATE rag_agent_trace SET incomplete=TRUE WHERE id=?", trace); } catch (RuntimeException ignored) { /* Fail open. */ }
    } }); } catch (RejectedExecutionException full) { state.incomplete = true; }
  }
  /** Records a running step before dispatch, with explicit parentage. @author czhcc */
  public Span start(AgentRunContext run, String name, String label, Object request) {
    UUID trace = run.getTraceId(); State state = trace == null ? null : states.get(trace);
    Span span = new Span(UUID.randomUUID(), trace, System.nanoTime());
    if (state != null) {
      int sequence = state.sequence.incrementAndGet(); long offset = (span.started() - state.started) / 1000000;
      UUID parent = run.getParentSpan();
      submit(trace, () -> jdbc.update("INSERT INTO rag_agent_trace_span(id,trace_id,parent_id,sequence,name,label,offset_ms) VALUES (?,?,?,?,?,?,?)", span.id(), trace, parent, sequence, name, label, offset));
      payload(span, "request", request);
    } return span;
  }
  /** Records safe completion metadata and a separately encrypted response. @author czhcc */
  public void finish(Span span, String status, Object response, Map<String, Object> summary) {
    payload(span, "response", response);
    long duration = (System.nanoTime() - span.started()) / 1000000;
    submit(span.trace(), () -> jdbc.update("UPDATE rag_agent_trace_span SET status=?,duration_ms=?,revision=revision+1,summary=?::JSONB WHERE id=?", status, duration, protection.sanitize(summary), span.id()));
  }
  private void payload(Span span, String side, Object value) {
    if (span.trace() == null) return;
    State state = states.get(span.trace()); if (state == null) return;
    try {
      String safe = protection.sanitize(value); int bytes = safe.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 2 * 1024 * 1024 || state.bytes + bytes > 20 * 1024 * 1024) { state.incomplete = true; return; } state.bytes += bytes;
      // 16K UTF-16 code units stay below 64KiB UTF-8, without breaking surrogate pairs.
      for (int offset = 0, part = 0; offset < safe.length(); part++) {
        int end = Math.min(safe.length(), offset + 16000); if (end < safe.length() && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        UUID id = UUID.randomUUID(); int index = part; String cipher = protection.encrypt(safe.substring(offset, end), id);
        submit(span.trace(), () -> jdbc.update("INSERT INTO rag_agent_trace_payload(id,span_id,side,part,cipher) VALUES (?,?,?,?,?)", id, span.id(), side, index, cipher)); offset = end;
      }
    } catch (RuntimeException failure) { state.incomplete = true; }
  }
  /** Queues terminal state after all prior steps, with a finite two-second flush. @author czhcc */
  public void end(UUID trace, String status) {
    if (trace == null) return; State state = states.get(trace); if (state == null) return;
    state.closed = true;
    submit(trace, () -> {
      try { jdbc.update("UPDATE rag_agent_trace SET status=?,incomplete=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, state.incomplete, trace); }
      finally { states.remove(trace); }
    });
    try { writer.submit(() -> {}).get(2, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    catch (ExecutionException | TimeoutException | RejectedExecutionException e) { state.incomplete = true; }
  }
  private void owned(UUID kb, UUID conversation, UUID turn, String actor) {
    access.requireAccess(kb, actor, false);
    if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rag_conversation c JOIN app_user u ON u.id=c.user_id JOIN rag_conversation_turn t ON t.knowledge_base_id=c.knowledge_base_id AND t.conversation_id=c.id WHERE c.knowledge_base_id=? AND c.id=? AND t.id=? AND u.username=?)", Boolean.class, kb, conversation, turn, actor))) throw new RagAccessException(404);
  }
  /** Returns summary pages; loaded running spans must be refreshed to observe revision changes. @author czhcc */
  public Map<String, Object> view(UUID kb, UUID conversation, UUID turn, String actor, int after, int size) {
    if (after < 0 || size < 1 || size > 200) throw new IllegalArgumentException(); owned(kb, conversation, turn, actor);
    var traces = jdbc.queryForList("SELECT id,status,incomplete,updated_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes' stale FROM rag_agent_trace WHERE knowledge_base_id=? AND conversation_id=? AND turn_id=?", kb, conversation, turn);
    if (traces.isEmpty()) return Map.of("status", "not_recorded", "availability", "not_recorded", "spans", List.of());
    var trace = traces.getFirst(); Object id = trace.get("id");
    String status = trace.get("status").toString(); if (status.equals("running") && Boolean.TRUE.equals(trace.get("stale")) && (!states.containsKey(id) || states.get(id).closed)) status = "interrupted";
    var spans = jdbc.query("SELECT id,parent_id,sequence,name,label,status,offset_ms,duration_ms,revision,summary FROM rag_agent_trace_span WHERE trace_id=? AND sequence>? ORDER BY sequence LIMIT ?", (rs, n) -> {
      Map<String, Object> s = new LinkedHashMap<>(); s.put("id", rs.getObject("id")); s.put("parentId", rs.getObject("parent_id")); s.put("sequence", rs.getInt("sequence"));
      s.put("name", rs.getString("name")); s.put("label", rs.getString("label")); s.put("status", rs.getString("status")); s.put("offsetMs", rs.getLong("offset_ms")); s.put("durationMs", rs.getLong("duration_ms")); s.put("revision", rs.getInt("revision")); s.put("summary", json.readValue(rs.getString("summary"), Object.class)); return s;
    }, id, after, size);
    String availability = Boolean.TRUE.equals(trace.get("incomplete")) ? "incomplete" : "available";
    State local = states.get(id); if (local != null && local.incomplete) availability = "incomplete";
    if (availability.equals("available") && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rag_agent_trace_payload p JOIN rag_agent_trace_span s ON s.id=p.span_id WHERE s.trace_id=? AND p.expires_at<=CURRENT_TIMESTAMP) AND NOT EXISTS(SELECT 1 FROM rag_agent_trace_payload p JOIN rag_agent_trace_span s ON s.id=p.span_id WHERE s.trace_id=? AND p.expires_at>CURRENT_TIMESTAMP)", Boolean.class, id, id))) availability = "expired";
    if (status.equals("interrupted")) for (var span : spans) if (span.get("status").equals("running")) span.put("status", "interrupted");
    return Map.of("traceId", id, "status", status, "availability", availability, "spans", spans);
  }
  /** Reads one encrypted segment only through its private owning turn. @author czhcc */
  public Map<String, Object> payload(UUID kb, UUID conversation, UUID turn, UUID span, String actor, String side, int part) {
    if (!Set.of("request", "response").contains(side) || part < 0 || part > 200) throw new IllegalArgumentException(); owned(kb, conversation, turn, actor);
    var ids = jdbc.queryForList("SELECT s.id FROM rag_agent_trace_span s JOIN rag_agent_trace t ON t.id=s.trace_id WHERE t.knowledge_base_id=? AND t.conversation_id=? AND t.turn_id=? AND s.id=?", kb, conversation, turn, span);
    if (ids.isEmpty()) throw new RagAccessException(404);
    var parts = jdbc.queryForList("SELECT id,cipher,expires_at<=CURRENT_TIMESTAMP expired FROM rag_agent_trace_payload WHERE span_id=? AND side=? AND part=?", span, side, part);
    if (parts.isEmpty()) return Map.of("availability", "not_recorded", "content", "", "parts", 0);
    if (Boolean.TRUE.equals(parts.getFirst().get("expired"))) return Map.of("availability", "expired", "content", "", "parts", 0);
    var row = parts.getFirst();
    try { return Map.of("availability", "available", "content", protection.decrypt(row.get("cipher").toString(), (UUID) row.get("id")),
        "parts", jdbc.queryForObject("SELECT COUNT(*) FROM rag_agent_trace_payload WHERE span_id=? AND side=?", Integer.class, span, side)); }
    catch (RuntimeException unavailable) { return Map.of("availability", "unavailable", "content", "", "parts", 0); }
  }
  /** Physically removes expired protocol bodies while retaining timeline summaries. @author czhcc */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
  public void expire() { jdbc.update("UPDATE rag_agent_trace_payload SET cipher='' WHERE expires_at<=CURRENT_TIMESTAMP AND cipher<>''"); }
  /** Aligns terminal trace state with the owned conversation turn's actual persistence outcome. @author czhcc */
  public void terminal(UUID kb, UUID conversation, UUID turn, String actor, String status) {
    if (conversation == null) return;
    try { owned(kb, conversation, turn, actor); jdbc.update("UPDATE rag_agent_trace SET status=?,updated_at=CURRENT_TIMESTAMP WHERE knowledge_base_id=? AND conversation_id=? AND turn_id=?", status, kb, conversation, turn); }
    catch (RuntimeException unavailable) { /* Trace capture cannot break completion or cancellation. */ }
  }
  /** Stops the bounded writer when the application closes. @author czhcc */
  @PreDestroy public void close() { writer.shutdown(); }
}
