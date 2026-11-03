package com.trs.app.rag.ingestion;

import com.trs.app.rag.*;
import com.trs.app.rag.index.IndexService;
import com.trs.app.rag.model.ModelFailure;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable document controls and short, serialized automatic index transitions. @author czhcc */
@Service
@RequiredArgsConstructor
public class DocumentWorkflowService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final IndexService indexes;

  /** Enqueues work in the same transaction that accepts a document revision. @author czhcc */
  public static void enqueue(JdbcTemplate jdbc, UUID kb, String actor, boolean refresh) {
    jdbc.update("INSERT INTO rag_document_index_queue(knowledge_base_id,requested_by,refresh_required)"
        + " SELECT ?,id,? FROM app_user WHERE username=? ON CONFLICT (knowledge_base_id) DO UPDATE"
        + " SET requested_by=EXCLUDED.requested_by,document_id=NULL,dirty=TRUE,error_code=NULL,revision=NEXTVAL('rag_document_pipeline_revision'),"
        + " refresh_required=rag_document_index_queue.refresh_required OR EXCLUDED.refresh_required", kb, refresh, actor);
  }

  /** Applies a membership-scoped document action, fencing old work before resumption. @author czhcc */
  @Transactional
  public void action(UUID kb, UUID document, String actor, String action) {
    access.requireAccess(kb, actor, true);
    if (!Set.of("pause", "run", "rebuild", "delete").contains(action)) throw new IllegalArgumentException();
    jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    var rows = jdbc.queryForList("SELECT d.current_revision_id,j.id AS job_id,j.status FROM rag_document d"
        + " JOIN rag_ingestion_job j ON j.knowledge_base_id=d.knowledge_base_id"
        + " AND j.revision_id=d.current_revision_id WHERE d.knowledge_base_id=? AND d.id=?"
        + " AND NOT d.deleted FOR UPDATE OF d,j", kb, document);
    if (rows.isEmpty()) throw new RagAccessException(404);
    var row = rows.getFirst();
    if (action.equals("pause") || action.equals("delete")) {
      // Clearing the token fences an in-flight parser even if run is clicked immediately.
      jdbc.update("UPDATE rag_ingestion_job SET status='cancelled',cancel_requested=TRUE,"
          + " lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL"
          + " WHERE knowledge_base_id=? AND id=? AND status<>'processed'", kb, row.get("job_id"));
    } else if (!"processed".equals(row.get("status"))) {
      jdbc.update("UPDATE rag_ingestion_job SET status='pending',attempts=0,cancel_requested=FALSE,"
          + " error_code=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,"
          + " requested_by=(SELECT id FROM app_user WHERE username=?) WHERE knowledge_base_id=? AND id=?",
          actor, kb, row.get("job_id"));
    }
    jdbc.update("UPDATE rag_document SET pipeline_requested=TRUE,pipeline_paused=?,pipeline_error=NULL,"
        + " pipeline_rebuild=?,deleted=? WHERE knowledge_base_id=? AND id=?", action.equals("pause"),
        action.equals("rebuild"), action.equals("delete"), kb, document);
    var queue = jdbc.queryForList("SELECT generation_id FROM rag_document_index_queue WHERE knowledge_base_id=?", kb);
    if (!queue.isEmpty() && queue.getFirst().get("generation_id") instanceof UUID generation)
      indexes.cancelAutomatic(kb, generation, actor);
    UUID previous = null;
    if (action.equals("delete")) {
      previous = jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb);
      // Prevent fresh queries from reading the deleted document through the previous generation.
      jdbc.update("UPDATE rag_knowledge_base SET active_generation_id=NULL WHERE id=?", kb);
    }
    enqueue(jdbc, kb, actor, action.equals("delete"));
    if (Set.of("run", "rebuild").contains(action))
      jdbc.update("UPDATE rag_document_index_queue SET document_id=? WHERE knowledge_base_id=?", document, kb);
    if (action.equals("delete") && previous != null)
      jdbc.update("UPDATE rag_document_index_queue SET expected_generation_id=? WHERE knowledge_base_id=?", previous, kb);
  }

  /** Lists bounded durable coordinators; the worker never holds model work in a transaction. @author czhcc */
  public List<Map<String, Object>> pending() {
    return jdbc.queryForList("SELECT q.knowledge_base_id,q.revision,q.generation_id FROM rag_document_index_queue q"
        + " WHERE q.error_code IS NULL AND (q.generation_id IS NOT NULL OR q.refresh_required OR EXISTS"
        + " (SELECT 1 FROM rag_document d JOIN rag_ingestion_job j ON j.knowledge_base_id=d.knowledge_base_id"
        + " AND j.revision_id=d.current_revision_id WHERE d.knowledge_base_id=q.knowledge_base_id"
        + " AND (q.document_id IS NULL OR d.id=q.document_id)"
        + " AND d.pipeline_requested AND d.pipeline_error IS NULL AND NOT d.pipeline_paused AND NOT d.deleted AND j.status='processed'))"
        + " ORDER BY q.knowledge_base_id LIMIT 100");
  }

  /** Advances one knowledge base under a database lock, without invoking any model. @author czhcc */
  @Transactional
  public void advance(UUID kb, long revision) {
    if (jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE SKIP LOCKED", kb).isEmpty()) return;
    var rows = jdbc.queryForList("SELECT q.*,u.username,b.active_generation_id FROM rag_document_index_queue q"
        + " JOIN app_user u ON u.id=q.requested_by JOIN rag_knowledge_base b ON b.id=q.knowledge_base_id"
        + " WHERE q.knowledge_base_id=? AND q.error_code IS NULL AND q.revision=?", kb, revision);
    if (rows.isEmpty()) return;
    var queue = rows.getFirst();
    String actor = (String) queue.get("username");
    access.requireAccess(kb, actor, true);
    UUID generation = (UUID) queue.get("generation_id");
    if (generation != null) {
      var state = jdbc.queryForMap("SELECT state,retry_at,error_code FROM rag_index_generation"
          + " WHERE knowledge_base_id=? AND id=?", kb, generation);
      String phase = (String) state.get("state");
      if (Set.of("pending", "building").contains(phase)) return;
      if (Boolean.TRUE.equals(queue.get("dirty"))) {
        indexes.cancelAutomatic(kb, generation, actor);
        jdbc.update("UPDATE rag_document_index_queue SET generation_id=NULL WHERE knowledge_base_id=?", kb);
        generation = null;
      } else if (phase.equals("ready")) {
        indexes.activateAutomatic(kb, generation, (UUID) queue.get("expected_generation_id"), actor);
        jdbc.update("UPDATE rag_document d SET pipeline_requested=FALSE,pipeline_rebuild=FALSE,pipeline_error=NULL WHERE"
            + " d.pipeline_error IS NULL AND"
            + " (?::UUID IS NULL OR d.id=?) AND"
            + " d.knowledge_base_id=? AND NOT d.deleted AND NOT d.pipeline_paused AND EXISTS"
            + " (SELECT 1 FROM rag_index_document i WHERE i.knowledge_base_id=d.knowledge_base_id"
            + " AND i.generation_id=? AND i.document_id=d.id AND i.revision_id=d.current_revision_id)",
            queue.get("document_id"), queue.get("document_id"), kb, generation);
        jdbc.update("UPDATE rag_document_index_queue SET generation_id=NULL,refresh_required=FALSE"
            + " WHERE knowledge_base_id=?", kb);
        clean(kb);
        return;
      } else {
        if (phase.equals("failed") && state.get("retry_at") != null) return;
        throw new ModelFailure(Objects.toString(state.get("error_code"), "index_build_failed"));
      }
    }
    // Pending/paused revisions do not block other documents. Keep their last published revision.
    Map<UUID, UUID> revisions = new LinkedHashMap<>();
    for (var row : jdbc.queryForList("SELECT d.id,CASE WHEN j.status='processed' AND d.pipeline_requested"
        + " AND d.pipeline_error IS NULL AND NOT d.pipeline_paused AND (q.document_id IS NULL OR d.id=q.document_id)"
        + " THEN d.current_revision_id ELSE old.revision_id END AS revision_id FROM rag_document d"
        + " JOIN rag_ingestion_job j ON j.knowledge_base_id=d.knowledge_base_id AND j.revision_id=d.current_revision_id"
        + " JOIN rag_knowledge_base b ON b.id=d.knowledge_base_id JOIN rag_document_index_queue q ON q.knowledge_base_id=b.id"
        + " LEFT JOIN rag_index_document old ON old.knowledge_base_id=b.id"
        + " AND old.generation_id=COALESCE(b.active_generation_id,CASE WHEN q.refresh_required THEN q.expected_generation_id END)"
        + " AND old.document_id=d.id"
        + " WHERE d.knowledge_base_id=? AND NOT d.deleted ORDER BY d.id", kb)) {
      if (row.get("revision_id") instanceof UUID captured) revisions.put((UUID) row.get("id"), captured);
    }
    boolean ready = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rag_document d"
        + " JOIN rag_ingestion_job j ON j.knowledge_base_id=d.knowledge_base_id AND j.revision_id=d.current_revision_id"
        + " JOIN rag_document_index_queue q ON q.knowledge_base_id=d.knowledge_base_id"
        + " WHERE d.knowledge_base_id=? AND d.pipeline_requested AND d.pipeline_error IS NULL AND NOT d.pipeline_paused AND NOT d.deleted"
        + " AND (q.document_id IS NULL OR d.id=q.document_id)"
        + " AND j.status='processed')", Boolean.class, kb));
    if (!ready && !Boolean.TRUE.equals(queue.get("refresh_required"))) { clean(kb); return; }
    if (revisions.isEmpty()) {
      jdbc.update("UPDATE rag_knowledge_base SET active_generation_id=NULL WHERE id=?", kb);
      jdbc.update("UPDATE rag_document_index_queue SET refresh_required=FALSE WHERE knowledge_base_id=?", kb);
      clean(kb);
      return;
    }
    var created = indexes.createAutomatic(kb, actor, revisions);
    jdbc.update("UPDATE rag_document_index_queue SET generation_id=?,expected_generation_id=?,dirty=FALSE"
        + " WHERE knowledge_base_id=?", created.get("id"), queue.get("active_generation_id"), kb);
  }

  private void clean(UUID kb) {
    jdbc.update("DELETE FROM rag_document_index_queue q WHERE knowledge_base_id=? AND generation_id IS NULL"
        + " AND NOT refresh_required AND NOT EXISTS(SELECT 1 FROM rag_document d"
        + " WHERE d.knowledge_base_id=q.knowledge_base_id AND NOT d.deleted AND d.pipeline_requested"
        + " AND (q.document_id IS NULL OR d.id=q.document_id))", kb);
  }

  /** Stores a sanitized terminal coordination error for explicit user retry. @author czhcc */
  @Transactional
  public void failed(UUID kb, long revision, UUID generation, String code) {
    jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    if (jdbc.update("UPDATE rag_document_index_queue SET error_code=? WHERE knowledge_base_id=? AND revision=?"
        + " AND generation_id IS NOT DISTINCT FROM ?", code, kb, revision, generation) != 1) return;
    jdbc.update("UPDATE rag_document SET pipeline_error=? WHERE knowledge_base_id=?"
        + " AND pipeline_requested AND NOT pipeline_paused AND NOT deleted AND EXISTS"
        + " (SELECT 1 FROM rag_document_index_queue q WHERE q.knowledge_base_id=rag_document.knowledge_base_id"
        + " AND (q.document_id IS NULL OR q.document_id=rag_document.id))", code, kb);
  }
}
