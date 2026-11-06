package com.trs.app.rag.agent;

import com.trs.app.rag.model.ModelVersion;
import com.trs.app.rag.query.QueryRequest;
import com.trs.app.rag.retrieval.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import lombok.Getter;

/** Request-private authority, budgets and evidence; never populated from model arguments. @author czhcc */
@Getter
public class AgentRunContext {
  private final Snapshot snapshot;
  private final String actor;
  private final QueryRequest request;
  private final ModelVersion answer, reranker;
  private final long deadline;
  private final List<Map<String, Object>> observations = new ArrayList<>(), statistics = new ArrayList<>();
  private final List<Result> retrievals = new ArrayList<>();
  private final Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
  private final AgentEvidenceRegistry evidence = new AgentEvidenceRegistry();
  private final Map<String, Integer> repetitions = new HashMap<>();
  private final Map<String, Map<String, Object>> successfulTools = new HashMap<>();
  private final Map<String, Deque<String>> pendingCalls = new HashMap<>();
  private final AgentContextManager.Memory contextMemory = new AgentContextManager.Memory();
  private int toolCalls;
  private int toolResultTokens = 2048;
  private String stopReason = "model_finished";
  private String rerankStatus;
  private UUID traceId, parentSpan;
  private ModelVersion keywordModel;
  /** Pins the mutable keyword configuration before any agent decision. @author czhcc */
  public void keywordModel(ModelVersion model) { this.keywordModel = model; }
  /** Captures server-owned context exactly once. @author czhcc */
  public AgentRunContext(Snapshot snapshot, String actor, QueryRequest request, ModelVersion answer, ModelVersion reranker, long deadline) {
    this.snapshot = snapshot; this.actor = actor; this.request = request; this.answer = answer; this.reranker = reranker; this.deadline = deadline;
    rerankStatus = request.isEnableRerank() ? reranker == null ? "not_configured" : "no_candidates" : "disabled";
  }
  /** Checks cancellation and the whole-turn deadline before every operation. @author czhcc */
  public void check() {
    if (Thread.currentThread().isInterrupted()) throw new RetrievalFailure("query_cancelled", 409);
    if (System.nanoTime() >= deadline) throw new RetrievalFailure("query_timeout", 504);
  }
  /** Bounds repeated calls even when a provider ignores loop instructions. @author czhcc */
  public void admit(String key, int maximum) {
    check(); if (++toolCalls > maximum) throw new RetrievalFailure("query_agent_tool_limit", 422);
    if (repetitions.merge(key, 1, Integer::sum) > 2 && !successfulTools.containsKey(key)) throw new RetrievalFailure("query_agent_no_progress", 422);
  }
  /** Schema discovery alone cannot justify finalizing factual research at a guard limit. @author czhcc */
  public boolean hasAnswerEvidence() {
    return observations.stream().anyMatch(o -> {
      if ("get_knowledge_schema".equals(o.get("tool"))) return false;
      if ("read_agent_context".equals(o.get("tool")))
        return o.get("result") instanceof Map<?, ?> recalled && "current_tool_evidence".equals(recalled.get("authority")) && !Objects.toString(recalled.get("content"), "").isBlank();
      if (o.get("result") instanceof Map<?, ?> result && Boolean.TRUE.equals(result.get("truncated")))
        return java.util.stream.Stream.of("items", "entities", "relations", "chunks").map(result::get)
            .anyMatch(v -> v instanceof List<?> list && !list.isEmpty());
      return true;
    });
  }
  /** Records why the server stopped further research, without changing evidence completeness. @author czhcc */
  public void stop(String reason) { stopReason = reason; }
  /** Reserves bounded observation space for each call in the next native tool batch. @author czhcc */
  public void toolResultTokens(int maximum) { toolResultTokens = Math.max(256, Math.min(2048, maximum)); }
  /** Bounds each provider timeout to the remaining turn deadline. @author czhcc */
  public ModelVersion bounded(ModelVersion v) {
    check(); int remaining = Math.max(1, (int) ((deadline - System.nanoTime()) / 1000000000L));
    return new ModelVersion(v.getId(), v.getProfileId(), v.getKind(), v.getBaseUrl(), v.getModel(), v.getCipher(), v.getDimensions(), v.getContextTokens(), v.getMaxOutputTokens(), Math.min(v.getTimeoutSeconds(), remaining), v.getExtraBody());
  }
  /** Associates trace state with this run only. @author czhcc */
  public void tracing(UUID trace, UUID parent) { traceId = trace; parentSpan = parent; }
  /** Tracks an applied reranking operation. @author czhcc */
  public void reranked() { rerankStatus = "applied"; }
  /** Registers an original source tuple and exposes only an opaque run-local handle. @author czhcc */
  public String source(Map<String, Object> value) {
    for (var entry : sources.entrySet()) if (entry.getValue().equals(value)) return entry.getKey();
    String id = "source-" + (sources.size() + 1); sources.put(id, value); return id;
  }
}
