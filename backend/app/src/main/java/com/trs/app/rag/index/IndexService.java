package com.trs.app.rag.index;

import com.trs.app.rag.*;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable generation queue, contribution checkpoints and explicit atomic activation. @author agent
 */
@Service
@RequiredArgsConstructor
public class IndexService {
  public static final String PROMPT_VERSION = "trs-typed-entity-json-v5";
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final ModelConfigurationService models;
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();

  private void owner(UUID kb, String actor) {
    if (access.requireAccess(kb, actor, false) != KnowledgeRole.OWNER)
      throw new RagAccessException(403);
  }

  /** Captures a trusted document snapshot for the automatic writer-owned pipeline. @author czhcc */
  @Transactional
  public Map<String, Object> createAutomatic(UUID kb, String actor, Map<UUID, UUID> revisions) {
    access.requireAccess(kb, actor, true);
    jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    Map<String, UUID> bindings = models.captureIndexBindings(kb);
    if (!bindings.keySet().containsAll(Set.of("extract", "embedding")))
      throw new ModelFailure("model_bindings_missing");
    UUID extract = bindings.get("extract"), summary = bindings.getOrDefault("summary", extract),
        embedding = bindings.get("embedding"), id = UUID.randomUUID();
    var vector = models.version(embedding);
    jdbc.update("INSERT INTO rag_index_generation(knowledge_base_id,id,requested_by,extract_version,"
        + " summary_version,embedding_version,dimensions,prompt_version,tokenizer,automatic)"
        + " SELECT ?,?,id,?,?,?,?,?,?,TRUE FROM app_user WHERE username=?", kb, id, extract, summary,
        embedding, vector.getDimensions(), PROMPT_VERSION, tokenizer.getName(), actor);
    for (var revision : revisions.entrySet()) {
      if (jdbc.update("INSERT INTO rag_index_document(knowledge_base_id,generation_id,document_id,revision_id)"
          + " SELECT ?,?,?,r.id FROM rag_document_revision r JOIN rag_document d"
          + " ON d.knowledge_base_id=r.knowledge_base_id AND d.id=r.document_id"
          + " JOIN rag_ingestion_job j ON j.knowledge_base_id=r.knowledge_base_id AND j.revision_id=r.id"
          + " WHERE r.knowledge_base_id=? AND r.document_id=? AND r.id=? AND NOT d.deleted AND j.status='processed'",
          kb, id, revision.getKey(), kb, revision.getKey(), revision.getValue()) != 1)
        throw new ModelFailure("index_inputs_not_ready");
    }
    jdbc.update("INSERT INTO rag_index_chunk(knowledge_base_id,generation_id,chunk_id) SELECT DISTINCT"
        + " d.knowledge_base_id,d.generation_id,dc.chunk_id FROM rag_index_document d JOIN rag_document_chunk dc"
        + " ON dc.knowledge_base_id=d.knowledge_base_id AND dc.revision_id=d.revision_id"
        + " WHERE d.knowledge_base_id=? AND d.generation_id=?", kb, id);
    int count = jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_chunk WHERE knowledge_base_id=? AND generation_id=?",
        Integer.class, kb, id);
    if (count == 0 || count > 20000) throw new ModelFailure("index_chunk_limit");
    reuseAutomaticContributions(kb, id);
    return get(kb, id, actor);
  }

  /** Copies complete compatible contributions, keeping row actions scoped to requested documents. @author czhcc */
  private void reuseAutomaticContributions(UUID kb, UUID generation) {
    jdbc.update("""
        WITH reusable AS MATERIALIZED (
          SELECT DISTINCT ON (c.chunk_id) c.chunk_id,c.extraction,v.dimensions,v.embedding
          FROM rag_index_chunk n JOIN rag_index_generation target
            ON target.knowledge_base_id=n.knowledge_base_id AND target.id=n.generation_id
          JOIN rag_document_index_queue q ON q.knowledge_base_id=n.knowledge_base_id
          JOIN rag_index_chunk c ON c.knowledge_base_id=n.knowledge_base_id AND c.chunk_id=n.chunk_id
            AND c.generation_id<>n.generation_id AND c.completed AND c.extraction IS NOT NULL
          JOIN rag_index_generation source ON source.knowledge_base_id=c.knowledge_base_id AND source.id=c.generation_id
          JOIN rag_index_chunk_vector v ON v.knowledge_base_id=c.knowledge_base_id
            AND v.generation_id=c.generation_id AND v.object_id=c.chunk_id AND v.dimensions=target.dimensions
          WHERE n.knowledge_base_id=? AND n.generation_id=?
            AND source.extract_version=target.extract_version AND source.summary_version=target.summary_version
            AND source.embedding_version=target.embedding_version AND source.dimensions=target.dimensions
            AND source.prompt_version=target.prompt_version AND source.tokenizer=target.tokenizer
            AND source.gleaning=target.gleaning
            AND NOT EXISTS (SELECT 1 FROM rag_index_document i JOIN rag_document d
              ON d.knowledge_base_id=i.knowledge_base_id AND d.id=i.document_id
              JOIN rag_document_chunk dc ON dc.knowledge_base_id=i.knowledge_base_id AND dc.revision_id=i.revision_id
              WHERE i.knowledge_base_id=n.knowledge_base_id AND i.generation_id=n.generation_id
                AND dc.chunk_id=n.chunk_id AND d.pipeline_requested AND NOT d.pipeline_paused
                AND (q.document_id IS NULL OR d.id=q.document_id)
                AND d.pipeline_error IS NULL AND d.pipeline_rebuild AND d.current_revision_id=i.revision_id)
          ORDER BY c.chunk_id,source.created_at DESC,source.id DESC
        ), copied AS (
          UPDATE rag_index_chunk n SET extraction=r.extraction,completed=TRUE FROM reusable r
          WHERE n.knowledge_base_id=? AND n.generation_id=? AND n.chunk_id=r.chunk_id RETURNING n.chunk_id
        )
        INSERT INTO rag_index_chunk_vector(knowledge_base_id,generation_id,object_id,dimensions,embedding)
        SELECT ?,?,r.chunk_id,r.dimensions,r.embedding FROM reusable r JOIN copied c ON c.chunk_id=r.chunk_id
        """, kb, generation, kb, generation, kb, generation);
    // A row action cannot silently re-extract other published documents under changed model versions.
    if (Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM rag_index_chunk c JOIN rag_document_index_queue q
          ON q.knowledge_base_id=c.knowledge_base_id WHERE c.knowledge_base_id=? AND c.generation_id=?
          AND NOT c.completed AND NOT EXISTS (
            SELECT 1 FROM rag_index_document i JOIN rag_document d
              ON d.knowledge_base_id=i.knowledge_base_id AND d.id=i.document_id
            JOIN rag_document_chunk dc ON dc.knowledge_base_id=i.knowledge_base_id AND dc.revision_id=i.revision_id
            WHERE i.knowledge_base_id=c.knowledge_base_id AND i.generation_id=c.generation_id AND dc.chunk_id=c.chunk_id
              AND d.pipeline_requested AND NOT d.pipeline_paused AND d.pipeline_error IS NULL
              AND (q.document_id IS NULL OR d.id=q.document_id)
              AND d.current_revision_id=i.revision_id))
        """, Boolean.class, kb, generation))) throw new ModelFailure("index_document_scope_incompatible");
  }

  /** Publishes only a complete automatic generation after checking the writer again. @author czhcc */
  @Transactional
  public void activateAutomatic(UUID kb, UUID id, UUID expected, String actor) {
    access.requireAccess(kb, actor, true);
    var base = jdbc.queryForMap("SELECT active_generation_id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    if (!Objects.equals(expected, base.get("active_generation_id"))) throw new RagAccessException(409);
    var rows = jdbc.queryForList("SELECT id FROM rag_index_generation WHERE knowledge_base_id=? AND id=?"
        + " AND automatic AND state='ready' AND NOT cancel_requested FOR SHARE", kb, id);
    if (rows.isEmpty()) throw new RagAccessException(409);
    integrity(kb, id);
    jdbc.update("UPDATE rag_knowledge_base SET active_generation_id=? WHERE id=?", id, kb);
  }

  /** Fences automatic execution immediately; ready snapshots remain immutable and unpublished. @author czhcc */
  @Transactional
  public void cancelAutomatic(UUID kb, UUID id, String actor) {
    access.requireAccess(kb, actor, true);
    jdbc.update("UPDATE rag_index_generation SET cancel_requested=TRUE,state='cancelled',lease_token=NULL,"
        + " lease_until=NULL,retry_at=NULL WHERE knowledge_base_id=? AND id=? AND automatic AND state<>'ready'", kb, id);
  }

  /**
   * Captures all current processed revisions and model versions as a new isolated index. @author
   * agent
   */
  @Transactional
  public Map<String, Object> create(UUID kb, String actor) {
    owner(kb, actor);
    jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    Map<String, UUID> bindings = models.captureIndexBindings(kb);
    if (!bindings.keySet().containsAll(Set.of("extract", "embedding")))
      throw new ModelFailure("model_bindings_missing");
    UUID extract = bindings.get("extract"),
        summary = bindings.getOrDefault("summary", extract),
        embedding = bindings.get("embedding");
    var vector = models.version(embedding);
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM rag_document d JOIN rag_ingestion_job j ON"
                + " j.knowledge_base_id=d.knowledge_base_id AND j.revision_id=d.current_revision_id"
                + " WHERE d.knowledge_base_id=? AND NOT d.deleted AND j.status<>'processed')",
            Boolean.class,
            kb))) throw new ModelFailure("index_inputs_not_ready");
    int count =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT dc.chunk_id) FROM rag_document d JOIN rag_document_chunk dc ON"
                + " dc.knowledge_base_id=d.knowledge_base_id AND"
                + " dc.revision_id=d.current_revision_id WHERE d.knowledge_base_id=? AND NOT d.deleted",
            Integer.class,
            kb);
    if (count == 0 || count > 20000)
      throw new IllegalArgumentException("Index requires 1..20000 processed chunks");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " rag_index_generation(knowledge_base_id,id,requested_by,extract_version,summary_version,embedding_version,dimensions,prompt_version,tokenizer)"
            + " SELECT ?,?,id,?,?,?,?,?,? FROM app_user WHERE username=?",
        kb,
        id,
        extract,
        summary,
        embedding,
        vector.getDimensions(),
        PROMPT_VERSION,
        tokenizer.getName(),
        actor);
    jdbc.update(
        "INSERT INTO rag_index_document(knowledge_base_id,generation_id,document_id,revision_id)"
            + " SELECT knowledge_base_id,?,id,current_revision_id FROM rag_document WHERE"
            + " knowledge_base_id=? AND NOT deleted",
        id,
        kb);
    jdbc.update(
        "INSERT INTO rag_index_chunk(knowledge_base_id,generation_id,chunk_id) SELECT DISTINCT"
            + " d.knowledge_base_id,d.generation_id,dc.chunk_id FROM rag_index_document d JOIN"
            + " rag_document_chunk dc ON dc.knowledge_base_id=d.knowledge_base_id AND"
            + " dc.revision_id=d.revision_id WHERE d.knowledge_base_id=? AND d.generation_id=?",
        kb,
        id);
    return get(kb, id, actor);
  }

  /** Lists bounded generation history, including current publication status. @author agent */
  @Transactional
  public List<Map<String, Object>> list(UUID kb, String actor) {
    access.requireAccess(kb, actor, false);
    return jdbc.query(
        "SELECT g.*,b.active_generation_id,(SELECT COUNT(*) FROM rag_index_chunk c WHERE"
            + " c.knowledge_base_id=g.knowledge_base_id AND c.generation_id=g.id)"
            + " total_chunks,(SELECT COUNT(*) FROM rag_index_chunk c WHERE"
            + " c.knowledge_base_id=g.knowledge_base_id AND c.generation_id=g.id AND c.completed)"
            + " completed_chunks FROM rag_index_generation g JOIN rag_knowledge_base b ON"
            + " b.id=g.knowledge_base_id WHERE g.knowledge_base_id=? ORDER BY"
            + " (g.id=b.active_generation_id) DESC NULLS LAST,g.created_at DESC,g.id LIMIT 50",
        (rs, n) -> view(rs),
        kb);
  }

  /** Reads one scoped generation without lease tokens or credentials. @author agent */
  @Transactional
  public Map<String, Object> get(UUID kb, UUID id, String actor) {
    access.requireAccess(kb, actor, false);
    var rows =
        jdbc.query(
            "SELECT g.*,b.active_generation_id,(SELECT COUNT(*) FROM rag_index_chunk c WHERE"
                + " c.knowledge_base_id=g.knowledge_base_id AND c.generation_id=g.id)"
                + " total_chunks,(SELECT COUNT(*) FROM rag_index_chunk c WHERE"
                + " c.knowledge_base_id=g.knowledge_base_id AND c.generation_id=g.id AND"
                + " c.completed) completed_chunks FROM rag_index_generation g JOIN"
                + " rag_knowledge_base b ON b.id=g.knowledge_base_id WHERE g.knowledge_base_id=?"
                + " AND g.id=?",
            (rs, n) -> view(rs),
            kb,
            id);
    if (rows.isEmpty()) throw new RagAccessException(404);
    return rows.getFirst();
  }

  private Map<String, Object> view(java.sql.ResultSet r) throws java.sql.SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    for (String[] fields :
        new String[][] {
          {"id", "id"},
          {"state", "state"},
          {"dimensions", "dimensions"},
          {"attempts", "attempts"},
          {"errorCode", "error_code"},
          {"extractVersion", "extract_version"},
          {"summaryVersion", "summary_version"},
          {"embeddingVersion", "embedding_version"},
          {"promptVersion", "prompt_version"},
          {"tokenizer", "tokenizer"},
          {"totalChunks", "total_chunks"},
          {"completedChunks", "completed_chunks"},
          {"createdAt", "created_at"},
          {"completedAt", "completed_at"}
        }) {
      Object value = r.getObject(fields[1]);
      m.put(fields[0], value instanceof java.sql.Timestamp ? value.toString() : value);
    }
    m.put("active", r.getObject("id").equals(r.getObject("active_generation_id")));
    return m;
  }

  /**
   * Switches or rolls back only a complete ready generation, using a compare-and-set
   * pointer. @author agent
   */
  @Transactional
  public void activate(UUID kb, UUID id, UUID expected, String actor) {
    owner(kb, actor);
    var base =
        jdbc.queryForMap(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    if (!Objects.equals(expected, base.get("active_generation_id")))
      throw new RagAccessException(409);
    var ready =
        jdbc.queryForList(
            "SELECT state FROM rag_index_generation WHERE knowledge_base_id=? AND id=? FOR SHARE",
            kb,
            id);
    if (ready.isEmpty()) throw new RagAccessException(404);
    if (!ready.getFirst().get("state").equals("ready")) throw new RagAccessException(409);
    integrity(kb, id);
    jdbc.update("UPDATE rag_knowledge_base SET active_generation_id=? WHERE id=?", id, kb);
  }

  /**
   * Requests durable cancellation without interrupting an atomic publish transaction. @author agent
   */
  @Transactional
  public void cancel(UUID kb, UUID id, String actor) {
    owner(kb, actor);
    int n =
        jdbc.update(
            "UPDATE rag_index_generation SET cancel_requested=TRUE,state=CASE WHEN state IN"
                + " ('pending','failed') THEN 'cancelled' ELSE state END WHERE knowledge_base_id=?"
                + " AND id=? AND state<>'ready'",
            kb,
            id);
    if (n == 0) throw new RagAccessException(409);
  }

  /** Retries the captured model space and retains valid per-chunk checkpoints. @author agent */
  @Transactional
  public void retry(UUID kb, UUID id, String actor) {
    owner(kb, actor);
    int n =
        jdbc.update(
            "UPDATE rag_index_generation SET"
                + " state='pending',attempts=0,cancel_requested=FALSE,error_code=NULL,lease_token=NULL,lease_until=NULL,retry_at=NULL,requested_by=(SELECT"
                + " id FROM app_user WHERE username=?) WHERE knowledge_base_id=? AND id=? AND state"
                + " IN ('failed','cancelled')",
            actor,
            kb,
            id);
    if (n == 0) throw new RagAccessException(409);
  }

  /** Claims one pending/expired/retryable generation with a fencing token. @author agent */
  @Transactional
  public Optional<IndexLease> claim() {
    jdbc.update(
        "UPDATE rag_index_generation SET state=CASE WHEN cancel_requested THEN 'cancelled' ELSE"
            + " 'failed' END,error_code=CASE WHEN cancel_requested THEN 'index_cancelled' ELSE"
            + " 'attempts_exhausted' END,lease_token=NULL,lease_until=NULL,retry_at=NULL WHERE"
            + " state='building' AND lease_until<CLOCK_TIMESTAMP() AND (attempts>=3 OR"
            + " cancel_requested)");
    var rows =
        jdbc.queryForList(
            "SELECT knowledge_base_id,id FROM rag_index_generation WHERE NOT cancel_requested AND"
                + " attempts<3 AND (state='pending' OR (state='building' AND"
                + " lease_until<CLOCK_TIMESTAMP()) OR (state='failed' AND"
                + " retry_at<=CLOCK_TIMESTAMP())) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP"
                + " LOCKED");
    if (rows.isEmpty()) return Optional.empty();
    var r = rows.getFirst();
    UUID kb = (UUID) r.get("knowledge_base_id"), id = (UUID) r.get("id"), token = UUID.randomUUID();
    jdbc.update(
        "UPDATE rag_index_generation SET"
            + " state='building',attempts=attempts+1,lease_token=?,lease_until=CLOCK_TIMESTAMP()+INTERVAL"
            + " '45 seconds',error_code=NULL,retry_at=NULL WHERE knowledge_base_id=? AND id=?",
        token,
        kb,
        id);
    return Optional.of(
        jdbc.queryForObject(
            "SELECT g.*,u.username FROM rag_index_generation g JOIN app_user u ON"
                + " u.id=g.requested_by WHERE g.knowledge_base_id=? AND g.id=?",
            (rs, n) ->
                new IndexLease(
                    kb,
                    id,
                    token,
                    rs.getString("username"),
                    rs.getObject("extract_version", UUID.class),
                    rs.getObject("summary_version", UUID.class),
                    rs.getObject("embedding_version", UUID.class),
                    rs.getInt("dimensions"),
                    rs.getInt("gleaning"),
                    rs.getString("prompt_version"),
                    rs.getString("tokenizer")),
            kb,
            id));
  }

  /** Extends only a live, noncancelled lease; cannot resurrect expired execution. @author agent */
  @Transactional
  public boolean heartbeat(IndexLease l) {
    return jdbc.update(
            "UPDATE rag_index_generation SET lease_until=CLOCK_TIMESTAMP()+INTERVAL '45 seconds'"
                + " WHERE knowledge_base_id=? AND id=? AND state='building' AND lease_token=? AND"
                + " NOT cancel_requested AND lease_until>CLOCK_TIMESTAMP()",
            l.getKnowledgeBaseId(),
            l.getId(),
            l.getToken())
        == 1;
  }

  /** Checks live fencing and current owner authorization at each stage boundary. @author agent */
  @Transactional
  public void check(IndexLease l) {
    boolean automatic = Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT automatic FROM rag_index_generation WHERE knowledge_base_id=? AND id=?",
        Boolean.class, l.getKnowledgeBaseId(), l.getId()));
    if (automatic) access.requireAccess(l.getKnowledgeBaseId(), l.getActor(), true);
    else owner(l.getKnowledgeBaseId(), l.getActor());
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM rag_index_generation WHERE knowledge_base_id=? AND id=?"
                + " AND state='building' AND lease_token=? AND lease_until>CLOCK_TIMESTAMP() AND"
                + " NOT cancel_requested)",
            Boolean.class,
            l.getKnowledgeBaseId(),
            l.getId(),
            l.getToken()))) throw new ModelFailure("index_lease_lost");
  }

  private void lock(IndexLease l) {
    check(l);
    jdbc.queryForList(
        "SELECT id FROM rag_index_generation WHERE knowledge_base_id=? AND id=? FOR UPDATE",
        l.getKnowledgeBaseId(),
        l.getId());
    check(l);
  }

  /**
   * Reads immutable source text and one stable heading breadcrumb per shared chunk. @author agent
   */
  @Transactional
  public List<Map<String, Object>> chunks(IndexLease l) {
    check(l);
    return jdbc.queryForList(
        "SELECT c.chunk_id,c.completed,c.extraction::TEXT,c2.content,(SELECT dc.headings::TEXT FROM"
            + " rag_index_document d JOIN rag_document_chunk dc ON"
            + " dc.knowledge_base_id=d.knowledge_base_id AND dc.revision_id=d.revision_id WHERE"
            + " d.knowledge_base_id=c.knowledge_base_id AND d.generation_id=c.generation_id AND"
            + " dc.chunk_id=c.chunk_id ORDER BY d.document_id,dc.chunk_order LIMIT 1) headings FROM"
            + " rag_index_chunk c JOIN rag_chunk c2 ON c2.knowledge_base_id=c.knowledge_base_id AND"
            + " c2.id=c.chunk_id WHERE c.knowledge_base_id=? AND c.generation_id=? ORDER BY"
            + " c.chunk_id",
        l.getKnowledgeBaseId(),
        l.getId());
  }

  /**
   * Saves incomplete streamed objects without marking a chunk complete or publishing vectors. @author czhcc
   */
  @Transactional
  public void extractionProgress(IndexLease l, UUID chunk, Extraction extraction) {
    lock(l);
    jdbc.update("UPDATE rag_index_chunk SET extraction=?::JSONB WHERE knowledge_base_id=?"
        + " AND generation_id=? AND chunk_id=? AND NOT completed", json.writeValueAsString(extraction),
        l.getKnowledgeBaseId(), l.getId(), chunk);
  }

  /** Saves a complete contribution and vector only after provider success. @author czhcc */
  @Transactional
  public void checkpoint(IndexLease l, UUID chunk, Extraction extraction, float[] vector) {
    lock(l);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT completed FROM rag_index_chunk WHERE knowledge_base_id=? AND generation_id=?"
                + " AND chunk_id=?",
            Boolean.class,
            l.getKnowledgeBaseId(),
            l.getId(),
            chunk))) {
      putVector("chunk", l, chunk, vector);
      jdbc.update(
          "UPDATE rag_index_chunk SET extraction=?::JSONB,completed=TRUE WHERE knowledge_base_id=?"
              + " AND generation_id=? AND chunk_id=?",
          json.writeValueAsString(extraction),
          l.getKnowledgeBaseId(),
          l.getId(),
          chunk);
    }
  }

  /**
   * Publishes the complete graph and its vectors as ready, leaving the active pointer
   * unchanged. @author agent
   */
  @Transactional
  public void complete(IndexLease l, GraphAssembler.Graph graph) {
    lock(l);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT NOT EXISTS(SELECT 1 FROM rag_index_chunk WHERE knowledge_base_id=? AND"
                + " generation_id=? AND (NOT completed OR extraction IS NULL))",
            Boolean.class,
            l.getKnowledgeBaseId(),
            l.getId()))) throw new ModelFailure("index_incomplete");
    jdbc.update(
        "DELETE FROM rag_index_relation WHERE knowledge_base_id=? AND generation_id=?",
        l.getKnowledgeBaseId(),
        l.getId());
    jdbc.update(
        "DELETE FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=?",
        l.getKnowledgeBaseId(),
        l.getId());
    for (var e : graph.getEntities()) {
      jdbc.update(
          "INSERT INTO"
              + " rag_index_entity(knowledge_base_id,generation_id,id,name,entity_type,description,source_ids,attributes)"
              + " VALUES (?,?,?,?,?,?,?::JSONB,?::JSONB)",
          l.getKnowledgeBaseId(),
          l.getId(),
          e.getId(),
          e.getName(),
          e.getType(),
          e.getDescription(),
          json.writeValueAsString(e.getSources()),
          json.writeValueAsString(EntityIdentity.attributes(e.getAttributes())));
      putVector("entity", l, e.getId(), e.getVector());
    }
    for (var r : graph.getRelations()) {
      jdbc.update(
          "INSERT INTO"
              + " rag_index_relation(knowledge_base_id,generation_id,id,source_id,target_id,description,keywords,weight,source_ids,relation_types)"
              + " VALUES (?,?,?,?,?,?,?,?,?::JSONB,?::JSONB)",
          l.getKnowledgeBaseId(),
          l.getId(),
          r.getId(),
          r.getSource(),
          r.getTarget(),
          r.getDescription(),
          r.getKeywords(),
          r.getWeight(),
          json.writeValueAsString(r.getSources()),
          json.writeValueAsString(r.getTypes()));
      putVector("relation", l, r.getId(), r.getVector());
    }
    integrity(l.getKnowledgeBaseId(), l.getId());
    check(l);
    jdbc.update(
        "UPDATE rag_index_generation SET"
            + " state='ready',completed_at=CLOCK_TIMESTAMP(),lease_token=NULL,lease_until=NULL,error_code=NULL,retry_at=NULL"
            + " WHERE knowledge_base_id=? AND id=?",
        l.getKnowledgeBaseId(),
        l.getId());
  }

  private void putVector(String kind, IndexLease l, UUID object, float[] values) {
    if (!Set.of("chunk", "entity", "relation").contains(kind) || values.length != l.getDimensions())
      throw new ModelFailure("embedding_dimension_mismatch");
    double norm = 0;
    for (float f : values) {
      if (!Float.isFinite(f)) throw new ModelFailure("embedding_invalid");
      norm += (double) f * f;
    }
    if (norm == 0) throw new ModelFailure("embedding_invalid");
    jdbc.update(
        "INSERT INTO rag_index_"
            + kind
            + "_vector(knowledge_base_id,generation_id,object_id,dimensions,embedding) VALUES"
            + " (?,?,?,?,?::public.vector) ON CONFLICT (knowledge_base_id,generation_id,object_id)"
            + " DO UPDATE SET embedding=EXCLUDED.embedding",
        l.getKnowledgeBaseId(),
        l.getId(),
        object,
        l.getDimensions(),
        Arrays.toString(values));
  }

  private void integrity(UUID kb, UUID id) {
    if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rag_index_document i"
        + " JOIN rag_document d ON d.knowledge_base_id=i.knowledge_base_id AND d.id=i.document_id"
        + " WHERE i.knowledge_base_id=? AND i.generation_id=? AND d.deleted)", Boolean.class, kb, id)))
      throw new ModelFailure("index_contains_deleted_document");
    for (String kind : List.of("chunk", "entity", "relation")) {
      int
          count =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM rag_index_"
                      + kind
                      + " WHERE knowledge_base_id=? AND generation_id=?",
                  Integer.class,
                  kb,
                  id),
          vectors =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM rag_index_"
                      + kind
                      + "_vector WHERE knowledge_base_id=? AND generation_id=?",
                  Integer.class,
                  kb,
                  id);
      if (count != vectors || kind.equals("chunk") && count == 0)
        throw new ModelFailure("index_incomplete");
    }
  }

  /**
   * Records a safe failure only for the current lease and schedules bounded transient
   * retries. @author agent
   */
  @Transactional
  public void fail(IndexLease l, String code) {
    boolean transientFailure =
        Set.of(
                "model_unavailable",
                "model_rate_limited",
                "model_http_error",
                "model_interrupted",
                "model_timeout")
            .contains(code);
    jdbc.update(
        "UPDATE rag_index_generation SET state=CASE WHEN cancel_requested THEN 'cancelled' ELSE"
            + " 'failed' END,error_code=CASE WHEN cancel_requested THEN 'index_cancelled' ELSE ?"
            + " END,retry_at=CASE WHEN NOT cancel_requested AND ? AND attempts<3 THEN"
            + " CLOCK_TIMESTAMP()+INTERVAL '5 seconds' ELSE NULL"
            + " END,lease_token=NULL,lease_until=NULL WHERE knowledge_base_id=? AND id=? AND"
            + " state='building' AND lease_token=? AND lease_until>CLOCK_TIMESTAMP()",
        code,
        transientFailure,
        l.getKnowledgeBaseId(),
        l.getId(),
        l.getToken());
  }

  /** Returns a bounded graph preview from a successful generation. @author agent */
  @Transactional
  public Map<String, Object> graph(UUID kb, UUID id, String actor) {
    var status = get(kb, id, actor);
    if (!"ready".equals(status.get("state"))) throw new RagAccessException(409);
    return Map.of(
        "entities",
        jdbc.queryForList(
            "SELECT id,name,entity_type,description,source_ids::TEXT,attributes::TEXT FROM rag_index_entity WHERE"
                + " knowledge_base_id=? AND generation_id=? ORDER BY name LIMIT 100",
            kb,
            id),
        "relations",
        jdbc.queryForList(
            "SELECT r.id,s.name source,t.name"
                + " target,r.description,r.keywords,r.weight,r.source_ids::TEXT,r.relation_types::TEXT FROM"
                + " rag_index_relation r JOIN rag_index_entity s ON"
                + " s.knowledge_base_id=r.knowledge_base_id AND s.generation_id=r.generation_id AND"
                + " s.id=r.source_id JOIN rag_index_entity t ON"
                + " t.knowledge_base_id=r.knowledge_base_id AND t.generation_id=r.generation_id AND"
                + " t.id=r.target_id WHERE r.knowledge_base_id=? AND r.generation_id=? ORDER BY"
                + " s.name,t.name LIMIT 100",
            kb,
            id));
  }
}
