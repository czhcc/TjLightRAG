package com.trs.app.rag.ingestion;

import com.trs.app.rag.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable queue and fenced short transactions; no parsing/model work occurs under a database lock.
 *
 * @author agent
 */
@Service
@RequiredArgsConstructor
@Transactional
public class JobService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final IngestionSettings settings;
  private final RevisionRepository revisions;
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * Claims one eligible database job with SKIP LOCKED and a fresh fencing token.
   *
   * @author agent
   */
  public Optional<JobLease> claim(String worker) {
    recover("cancelled", "cancelled", "cancel_requested AND lease_until<CLOCK_TIMESTAMP()", null);
    recover(
        "failed",
        "failed",
        "NOT cancel_requested AND attempts>=max_attempts AND lease_until<CLOCK_TIMESTAMP()",
        "attempts_exhausted");
    var rows =
        jdbc.queryForList(
            "SELECT knowledge_base_id,id FROM rag_ingestion_job WHERE NOT cancel_requested AND"
                + " attempts<max_attempts AND (status='pending' OR (status IN"
                + " ('parsing','processing') AND lease_until<CLOCK_TIMESTAMP()) OR (status='failed'"
                + " AND next_attempt_at<=CLOCK_TIMESTAMP())) ORDER BY created_at,id LIMIT 1 FOR"
                + " UPDATE SKIP LOCKED");
    if (rows.isEmpty()) return Optional.empty();
    var row = rows.getFirst();
    UUID kb = (UUID) row.get("knowledge_base_id"),
        id = (UUID) row.get("id"),
        token = UUID.randomUUID();
    jdbc.update(
        "UPDATE rag_ingestion_job SET"
            + " status='parsing',attempts=attempts+1,lease_token=?,lease_owner=?,lease_until=CLOCK_TIMESTAMP()+?*INTERVAL"
            + " '1 second',next_attempt_at=NULL,error_code=NULL,updated_at=CLOCK_TIMESTAMP() WHERE"
            + " knowledge_base_id=? AND id=?",
        token,
        worker,
        settings.getLeaseSeconds(),
        kb,
        id);
    event(kb, id, "claimed", null);
    return Optional.of(
        jdbc.queryForObject(
            "SELECT j.id,j.knowledge_base_id,j.revision_id,j.attempts,u.username FROM"
                + " rag_ingestion_job j JOIN app_user u ON u.id=j.requested_by WHERE"
                + " j.knowledge_base_id=? AND j.id=?",
            (rs, n) ->
                new JobLease(
                    id,
                    kb,
                    rs.getObject("revision_id", UUID.class),
                    token,
                    rs.getString("username"),
                    rs.getInt("attempts")),
            kb,
            id));
  }

  private void recover(String status, String event, String condition, String code) {
    jdbc.update(
        "WITH recovered AS (UPDATE rag_ingestion_job SET"
            + " status=?,error_code=?,lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,updated_at=CLOCK_TIMESTAMP()"
            + " WHERE status IN ('parsing','processing') AND "
            + condition
            + " RETURNING knowledge_base_id,id) INSERT INTO"
            + " rag_job_event(knowledge_base_id,job_id,event,code) SELECT knowledge_base_id,id,?,?"
            + " FROM recovered",
        status,
        code,
        event,
        code);
  }

  /**
   * Loads immutable input only for a live lease and authorized execution user.
   *
   * @author agent
   */
  public DocumentInput load(JobLease lease) {
    access.requireAccess(lease.getKbId(), lease.getActor(), true);
    live(lease, false);
    var metadata =
        revisions
            .findById(new ScopedRevisionId(lease.getKbId(), lease.getRevisionId()))
            .orElseThrow(LeaseLostException::new);
    if (!metadata.getTokenizer().equals(tokenizer.getName())
        || !metadata.getChunkStrategy().equals(DocumentService.STRATEGY)
        || !metadata.getParserVersion().equals(DocumentService.PARSER))
      throw new ProcessingFailure("unsupported_version");
    byte[] original =
        jdbc.queryForObject(
            "SELECT original FROM rag_document_blob WHERE knowledge_base_id=? AND revision_id=?",
            byte[].class,
            lease.getKbId(),
            lease.getRevisionId());
    if (original == null || !DocumentService.hash(original).equals(metadata.getContentHash()))
      throw new ProcessingFailure("source_integrity");
    return new DocumentInput(
        original,
        metadata.getFormat(),
        metadata.getChunkSize(),
        metadata.getOverlap(),
        metadata.getParsedText());
  }

  /**
   * Renews a live lease without reviving stale or cancelled workers.
   *
   * @author agent
   */
  public boolean heartbeat(JobLease lease) {
    return jdbc.update(
            "UPDATE rag_ingestion_job SET lease_until=CLOCK_TIMESTAMP()+?*INTERVAL '1 second' WHERE"
                + " knowledge_base_id=? AND id=? AND lease_token=? AND status IN"
                + " ('parsing','processing') AND NOT cancel_requested AND"
                + " lease_until>CLOCK_TIMESTAMP()",
            settings.getLeaseSeconds(),
            lease.getKbId(),
            lease.getId(),
            lease.getToken())
        == 1;
  }

  /**
   * Commits parsed text as a recoverable, lease-guarded checkpoint.
   *
   * @author agent
   */
  public void checkpoint(JobLease lease, String parsed) {
    access.requireAccess(lease.getKbId(), lease.getActor(), true);
    live(lease, true);
    jdbc.update(
        "UPDATE rag_document_revision SET parsed_text=?,char_count=? WHERE knowledge_base_id=? AND"
            + " id=?",
        parsed,
        parsed.codePointCount(0, parsed.length()),
        lease.getKbId(),
        lease.getRevisionId());
    if (jdbc.update(
            "UPDATE rag_ingestion_job SET status='processing',updated_at=CLOCK_TIMESTAMP() WHERE"
                + " knowledge_base_id=? AND id=? AND lease_token=? AND NOT cancel_requested AND"
                + " lease_until>CLOCK_TIMESTAMP()",
            lease.getKbId(),
            lease.getId(),
            lease.getToken())
        != 1) throw new LeaseLostException();
    event(lease.getKbId(), lease.getId(), "parsed", null);
  }

  /**
   * Atomically publishes shared blocks and ordered source links for a live lease.
   *
   * @author agent
   */
  public void complete(JobLease lease, List<ChunkPiece> pieces) {
    access.requireAccess(lease.getKbId(), lease.getActor(), true);
    live(lease, true);
    String text =
        jdbc.queryForObject(
            "SELECT parsed_text FROM rag_document_revision WHERE knowledge_base_id=? AND id=?",
            String.class,
            lease.getKbId(),
            lease.getRevisionId());
    if (text == null || pieces.isEmpty() || pieces.size() > settings.getMaxChunks())
      throw new IllegalArgumentException("Invalid publication");
    int[] source = text.codePoints().toArray();
    var contents = new TreeMap<String, String>();
    var hashes = new ArrayList<String>();
    for (var piece : pieces) {
      if (piece.getSourceStart() < 0
          || piece.getSourceEnd() > source.length
          || piece.getSourceEnd() <= piece.getSourceStart()
          || !new String(
                  source, piece.getSourceStart(), piece.getSourceEnd() - piece.getSourceStart())
              .equals(piece.getContent()))
        throw new IllegalArgumentException("Invalid source interval");
      String hash = DocumentService.hash(piece.getContent().getBytes(StandardCharsets.UTF_8));
      hashes.add(hash);
      contents.put(hash, piece.getContent());
    }
    // Stable lock order avoids deadlocks when different revisions share blocks in different orders.
    var ids = new HashMap<String, UUID>();
    for (var entry : contents.entrySet()) {
      UUID candidate = UUID.randomUUID();
      UUID chunk =
          jdbc.queryForObject(
              "INSERT INTO rag_chunk(knowledge_base_id,id,content,content_hash) VALUES (?,?,?,?) ON"
                  + " CONFLICT (knowledge_base_id,content_hash) DO UPDATE SET"
                  + " content_hash=EXCLUDED.content_hash RETURNING id",
              UUID.class,
              lease.getKbId(),
              candidate,
              entry.getValue(),
              entry.getKey());
      ids.put(entry.getKey(), chunk);
    }
    int order = 0;
    for (var piece : pieces) {
      jdbc.update(
          "INSERT INTO rag_document_chunk"
              + " (knowledge_base_id,revision_id,chunk_order,chunk_id,tokens,token_start,token_end,source_start,source_end,headings)"
              + " VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS JSONB))",
          lease.getKbId(),
          lease.getRevisionId(),
          order,
          ids.get(hashes.get(order)),
          piece.getTokens(),
          piece.getTokenStart(),
          piece.getTokenEnd(),
          piece.getSourceStart(),
          piece.getSourceEnd(),
          json.writeValueAsString(piece.getHeadings()));
      order++;
    }
    if (jdbc.update(
            "UPDATE rag_ingestion_job SET"
                + " status='processed',lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,error_code=NULL,updated_at=CLOCK_TIMESTAMP()"
                + " WHERE knowledge_base_id=? AND id=? AND lease_token=? AND NOT cancel_requested"
                + " AND lease_until>CLOCK_TIMESTAMP()",
            lease.getKbId(),
            lease.getId(),
            lease.getToken())
        != 1) throw new LeaseLostException();
    event(lease.getKbId(), lease.getId(), "published", null);
  }

  /**
   * Records a sanitized error and schedules retries within the persisted attempt limit.
   *
   * @author agent
   */
  public void fail(JobLease lease, String code, boolean retryable) {
    boolean again = retryable;
    int changed =
        jdbc.update(
            "UPDATE rag_ingestion_job SET status='failed',error_code=?,next_attempt_at=CASE WHEN ?"
                + " AND attempts<max_attempts THEN CLOCK_TIMESTAMP()+?*INTERVAL '1 second' ELSE"
                + " NULL"
                + " END,lease_token=NULL,lease_owner=NULL,lease_until=NULL,updated_at=CLOCK_TIMESTAMP()"
                + " WHERE knowledge_base_id=? AND id=? AND lease_token=? AND status IN"
                + " ('parsing','processing') AND NOT cancel_requested AND"
                + " lease_until>CLOCK_TIMESTAMP()",
            code,
            again,
            2 * Math.min(16, lease.getAttempts()),
            lease.getKbId(),
            lease.getId(),
            lease.getToken());
    if (changed == 1) event(lease.getKbId(), lease.getId(), "failed", code);
    else settleCancellation(lease);
  }

  /**
   * Finishes a requested cancellation only for the current worker token.
   *
   * @author agent
   */
  public void settleCancellation(JobLease lease) {
    int changed =
        jdbc.update(
            "UPDATE rag_ingestion_job SET"
                + " status='cancelled',lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,updated_at=CLOCK_TIMESTAMP()"
                + " WHERE knowledge_base_id=? AND id=? AND lease_token=? AND status IN"
                + " ('parsing','processing') AND cancel_requested",
            lease.getKbId(),
            lease.getId(),
            lease.getToken());
    if (changed == 1) event(lease.getKbId(), lease.getId(), "cancelled", null);
  }

  /**
   * Persists a cancellation request after checking write permission.
   *
   * @author agent
   */
  public void cancel(UUID kb, String username, UUID id) {
    access.requireAccess(kb, username, true);
    var status = locked(kb, id);
    if (status.equals("processed")) throw new IllegalArgumentException("Already processed");
    if (status.equals("cancelled")) return;
    boolean queued = Set.of("pending", "failed").contains(status);
    jdbc.update(
        "UPDATE rag_ingestion_job SET cancel_requested=TRUE,status=CASE WHEN ? THEN 'cancelled'"
            + " ELSE status END,next_attempt_at=NULL,updated_at=CLOCK_TIMESTAMP() WHERE"
            + " knowledge_base_id=? AND id=?",
        queued,
        kb,
        id);
    event(kb, id, queued ? "cancelled" : "cancel_requested", null);
  }

  /**
   * Starts an explicit new attempt cycle as the authorized requesting user.
   *
   * @author agent
   */
  public void retry(UUID kb, String username, UUID id) {
    access.requireAccess(kb, username, true);
    String status = locked(kb, id);
    if (!Set.of("failed", "cancelled").contains(status))
      throw new IllegalArgumentException("Only failed/cancelled jobs can be retried");
    Long user =
        jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    jdbc.update(
        "UPDATE rag_ingestion_job SET"
            + " status='pending',attempts=0,cancel_requested=FALSE,error_code=NULL,next_attempt_at=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL,requested_by=?,updated_at=CLOCK_TIMESTAMP()"
            + " WHERE knowledge_base_id=? AND id=?",
        user,
        kb,
        id);
    event(kb, id, "retried", null);
  }

  /**
   * Returns one resource only within the authenticated knowledge base.
   *
   * @author agent
   */
  public JobView get(UUID kb, String username, UUID id) {
    access.requireAccess(kb, username, false);
    var rows = jdbc.query(JOB_SQL + " WHERE j.knowledge_base_id=? AND j.id=?", this::view, kb, id);
    if (rows.isEmpty()) throw new RagAccessException(404);
    var job = rows.getFirst();
    job.setEvents(
        jdbc.query(
            "SELECT event,code,created_at FROM rag_job_event WHERE knowledge_base_id=? AND job_id=?"
                + " ORDER BY id DESC LIMIT 100",
            (rs, n) ->
                new JobEvent(
                    rs.getString("event"),
                    rs.getString("code"),
                    rs.getTimestamp("created_at").toInstant()),
            kb,
            id));
    return job;
  }

  /**
   * Lists permission-scoped resources with explicit pagination bounds.
   *
   * @author agent
   */
  public PageSlice<JobView> list(UUID kb, String username, int page, int size) {
    access.requireAccess(kb, username, false);
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
    var rows =
        jdbc.query(
            JOB_SQL
                + " WHERE j.knowledge_base_id=? ORDER BY j.created_at DESC,j.id LIMIT ? OFFSET ?",
            this::view,
            kb,
            size,
            (long) page * size);
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_ingestion_job WHERE knowledge_base_id=?", Long.class, kb);
    return new PageSlice<>(rows, count, page, size);
  }

  private void live(JobLease lease, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT id FROM rag_ingestion_job WHERE knowledge_base_id=? AND id=? AND lease_token=?"
                + " AND status IN ('parsing','processing') AND NOT cancel_requested AND"
                + " lease_until>CLOCK_TIMESTAMP()"
                + (lock ? " FOR UPDATE" : ""),
            lease.getKbId(),
            lease.getId(),
            lease.getToken());
    if (rows.isEmpty()) throw new LeaseLostException();
  }

  private String locked(UUID kb, UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT status FROM rag_ingestion_job WHERE knowledge_base_id=? AND id=? FOR UPDATE",
            String.class,
            kb,
            id);
    if (rows.isEmpty()) throw new RagAccessException(404);
    return rows.getFirst();
  }

  private void event(UUID kb, UUID job, String event, String code) {
    jdbc.update(
        "INSERT INTO rag_job_event(knowledge_base_id,job_id,event,code) VALUES (?,?,?,?)",
        kb,
        job,
        event,
        code);
  }

  private JobView view(ResultSet rs, int n) throws SQLException {
    return JobView.builder()
        .id(rs.getObject("id", UUID.class))
        .documentId(rs.getObject("document_id", UUID.class))
        .revisionId(rs.getObject("revision_id", UUID.class))
        .sourceName(rs.getString("source_name"))
        .status(rs.getString("status"))
        .attempts(rs.getInt("attempts"))
        .maxAttempts(rs.getInt("max_attempts"))
        .cancelRequested(rs.getBoolean("cancel_requested"))
        .errorCode(rs.getString("error_code"))
        .createdAt(rs.getTimestamp("created_at").toInstant())
        .updatedAt(rs.getTimestamp("updated_at").toInstant())
        .events(List.of())
        .build();
  }

  private static final String JOB_SQL =
      "SELECT"
          + " j.id,j.revision_id,r.document_id,d.source_name,j.status,j.attempts,j.max_attempts,j.cancel_requested,j.error_code,j.created_at,j.updated_at"
          + " FROM rag_ingestion_job j JOIN rag_document_revision r ON"
          + " r.knowledge_base_id=j.knowledge_base_id AND r.id=j.revision_id JOIN rag_document d ON"
          + " d.knowledge_base_id=r.knowledge_base_id AND d.id=r.document_id";
}
