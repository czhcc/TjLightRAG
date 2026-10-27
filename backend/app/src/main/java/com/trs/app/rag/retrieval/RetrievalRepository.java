package com.trs.app.rag.retrieval;

import com.trs.app.rag.KnowledgeBaseService;
import com.trs.app.rag.RagAccessException;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short, permission-scoped SQL reads; every vector and graph read uses the captured
 * generation. @author agent
 */
@Repository
@RequiredArgsConstructor
@Transactional(timeout = 15)
public class RetrievalRepository {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final JsonMapper json = JsonMapper.builder().build();
  private static final int EXPANSION_LIMIT = 2000, SOURCE_LIMIT = 5000;
  private static final String NODE =
      "SELECT n.*, (SELECT COUNT(*) FROM rag_index_relation e WHERE"
          + " e.knowledge_base_id=n.knowledge_base_id AND e.generation_id=n.generation_id AND"
          + " (e.source_id=n.id OR e.target_id=n.id)) degree";
  private static final String EDGE =
      "SELECT r.*,s.name source_name,t.name target_name,((SELECT COUNT(*) FROM rag_index_relation e"
          + " WHERE e.knowledge_base_id=r.knowledge_base_id AND e.generation_id=r.generation_id AND"
          + " (e.source_id=r.source_id OR e.target_id=r.source_id))+(SELECT COUNT(*) FROM"
          + " rag_index_relation e WHERE e.knowledge_base_id=r.knowledge_base_id AND"
          + " e.generation_id=r.generation_id AND (e.source_id=r.target_id OR"
          + " e.target_id=r.target_id))) rank";
  private static final String EDGE_FROM =
      " FROM rag_index_relation r JOIN rag_index_entity s ON"
          + " s.knowledge_base_id=r.knowledge_base_id AND s.generation_id=r.generation_id AND"
          + " s.id=r.source_id JOIN rag_index_entity t ON t.knowledge_base_id=r.knowledge_base_id"
          + " AND t.generation_id=r.generation_id AND t.id=r.target_id";
  private static final String HEADINGS =
      "COALESCE((SELECT JSONB_AGG(LEFT(h.value,256) ORDER BY h.ordinal) FROM"
          + " JSONB_ARRAY_ELEMENTS_TEXT(dc.headings) WITH ORDINALITY h(value,ordinal) WHERE"
          + " h.ordinal<=8),'[]'::JSONB)::TEXT";

  /**
   * Captures the active ready index and immutable model IDs in one database statement. @author
   * agent
   */
  public Snapshot capture(UUID kb, String actor) {
    access.requireAccess(kb, actor, false);
    var rows =
        jdbc.query(
            "SELECT g.* ,COALESCE((SELECT version_id FROM rag_model_binding WHERE"
                + " role='keywords'),g.extract_version) keyword_version FROM rag_knowledge_base b"
                + " JOIN rag_index_generation g ON g.knowledge_base_id=b.id AND"
                + " g.id=b.active_generation_id WHERE b.id=? AND g.state='ready'",
            (rs, n) ->
                new Snapshot(
                    kb,
                    rs.getObject("id", UUID.class),
                    rs.getObject("embedding_version", UUID.class),
                    rs.getObject("keyword_version", UUID.class),
                    rs.getInt("dimensions"),
                    rs.getString("tokenizer")),
            kb);
    if (rows.isEmpty()) throw new RetrievalFailure("retrieval_no_active_index", 409);
    return rows.getFirst();
  }

  /**
   * Rechecks membership and index readiness without switching to a newer active generation. @author
   * agent
   */
  public void check(Snapshot snapshot, String actor) {
    access.requireAccess(snapshot.getKnowledgeBaseId(), actor, false);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM rag_index_generation WHERE knowledge_base_id=? AND id=?"
                + " AND state='ready')",
            Boolean.class,
            snapshot.getKnowledgeBaseId(),
            snapshot.getGenerationId()))) throw new RagAccessException(404);
  }

  private String vector(Snapshot snapshot, float[] values) {
    if (values == null || values.length != snapshot.getDimensions())
      throw new RetrievalFailure("retrieval_vector_space_mismatch", 409);
    double norm = 0;
    for (float value : values) {
      if (!Float.isFinite(value)) throw new RetrievalFailure("retrieval_vector_invalid", 502);
      norm += (double) value * value;
    }
    if (norm == 0) throw new RetrievalFailure("retrieval_vector_invalid", 502);
    return Arrays.toString(values);
  }

  private String ids(Collection<String> values) {
    return "{"
        + String.join(",", values.stream().map(s -> UUID.fromString(s).toString()).toList())
        + "}";
  }

  private List<String> sourceIds(String value) {
    var result = json.readValue(value, List.class);
    if (result.size() > 20000) throw new RetrievalFailure("retrieval_resource_limit", 413);
    List<String> ids = new ArrayList<>();
    for (Object raw : result) {
      try {
        ids.add(UUID.fromString(raw.toString()).toString());
      } catch (RuntimeException corrupt) {
        throw new RetrievalFailure("retrieval_index_incomplete", 409);
      }
    }
    return ids;
  }

  private Node node(ResultSet rs, Double score) throws SQLException {
    return new Node(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("entity_type"),
        rs.getString("description"),
        sourceIds(rs.getString("source_ids")),
        rs.getInt("degree"),
        score);
  }

  private Edge edge(ResultSet rs, Double score) throws SQLException {
    String a = rs.getString("source_name"), b = rs.getString("target_name");
    if (a.compareTo(b) > 0) {
      String tmp = a;
      a = b;
      b = tmp;
    }
    return new Edge(
        rs.getString("id"),
        a,
        b,
        rs.getString("description"),
        rs.getString("keywords"),
        rs.getDouble("weight"),
        sourceIds(rs.getString("source_ids")),
        rs.getInt("rank"),
        score);
  }

  /**
   * Exact entity recall with deterministic score ties and no access to legacy V2 vectors. @author
   * agent
   */
  public List<Node> entities(Snapshot s, String actor, float[] values, int k, double threshold) {
    check(s, actor);
    String v = vector(s, values);
    return jdbc.query(
        NODE
            + ",1-(v.embedding <=> ?::public.vector) score FROM rag_index_entity n JOIN"
            + " rag_index_entity_vector v ON v.knowledge_base_id=n.knowledge_base_id AND"
            + " v.generation_id=n.generation_id AND v.object_id=n.id WHERE n.knowledge_base_id=?"
            + " AND n.generation_id=? AND (v.embedding <=> ?::public.vector)<=? ORDER BY"
            + " v.embedding <=> ?::public.vector,n.id LIMIT ?",
        (rs, n) -> node(rs, rs.getDouble("score")),
        v,
        s.getKnowledgeBaseId(),
        s.getGenerationId(),
        v,
        1 - threshold,
        v,
        k);
  }

  /**
   * Exact edge recall preserving cosine order rather than sorting by graph weight. @author agent
   */
  public List<Edge> relations(Snapshot s, String actor, float[] values, int k, double threshold) {
    check(s, actor);
    String v = vector(s, values);
    return jdbc.query(
        EDGE
            + ",1-(v.embedding <=> ?::public.vector) score"
            + EDGE_FROM
            + " JOIN rag_index_relation_vector v ON v.knowledge_base_id=r.knowledge_base_id AND"
            + " v.generation_id=r.generation_id AND v.object_id=r.id WHERE r.knowledge_base_id=?"
            + " AND r.generation_id=? AND (v.embedding <=> ?::public.vector)<=? ORDER BY"
            + " v.embedding <=> ?::public.vector,r.id LIMIT ?",
        (rs, n) -> edge(rs, rs.getDouble("score")),
        v,
        s.getKnowledgeBaseId(),
        s.getGenerationId(),
        v,
        1 - threshold,
        v,
        k);
  }

  /**
   * Exact chunk recall; provenance is hydrated only after branch and graph-budget
   * selection. @author agent
   */
  public List<Chunk> chunks(Snapshot s, String actor, float[] values, int k, double threshold) {
    check(s, actor);
    String v = vector(s, values);
    return jdbc.query(
        "SELECT c.id,c.content,1-(v.embedding <=> ?::public.vector) score FROM"
            + " rag_index_chunk_vector v JOIN rag_chunk c ON"
            + " c.knowledge_base_id=v.knowledge_base_id AND c.id=v.object_id WHERE"
            + " v.knowledge_base_id=? AND v.generation_id=? AND (v.embedding <=>"
            + " ?::public.vector)<=? ORDER BY v.embedding <=> ?::public.vector,v.object_id LIMIT ?",
        (rs, n) ->
            new Chunk(
                rs.getString("id"),
                rs.getString("content"),
                "",
                List.of(),
                rs.getDouble("score"),
                List.of()),
        v,
        s.getKnowledgeBaseId(),
        s.getGenerationId(),
        v,
        1 - threshold,
        v,
        k);
  }

  /**
   * Loads bounded incident edges, rejecting excessive expansion instead of silently losing
   * candidates. @author agent
   */
  public List<Edge> adjacent(Snapshot s, String actor, List<Node> nodes) {
    check(s, actor);
    if (nodes.isEmpty()) return List.of();
    String ids = ids(nodes.stream().map(Node::getId).toList());
    var edges =
        jdbc.query(
            EDGE
                + EDGE_FROM
                + " WHERE r.knowledge_base_id=? AND r.generation_id=? AND"
                + " (r.source_id=ANY(?::UUID[]) OR r.target_id=ANY(?::UUID[])) ORDER BY r.id LIMIT"
                + " ?",
            (rs, n) -> edge(rs, null),
            s.getKnowledgeBaseId(),
            s.getGenerationId(),
            ids,
            ids,
            EXPANSION_LIMIT + 1);
    if (edges.size() > EXPANSION_LIMIT)
      throw new RetrievalFailure("retrieval_expansion_limit", 413);
    return edges;
  }

  /**
   * Loads endpoint nodes in first-appearance order of the recalled, canonical edges. @author agent
   */
  public List<Node> endpoints(Snapshot s, String actor, List<Edge> edges) {
    check(s, actor);
    if (edges.isEmpty()) return List.of();
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (var edge : edges) {
      names.add(edge.getSource());
      names.add(edge.getTarget());
    }
    List<Object> args = new ArrayList<>();
    args.add(s.getKnowledgeBaseId());
    args.add(s.getGenerationId());
    args.addAll(names);
    var rows =
        jdbc.query(
            NODE
                + " FROM rag_index_entity n WHERE n.knowledge_base_id=? AND n.generation_id=? AND"
                + " n.name IN ("
                + String.join(",", Collections.nCopies(names.size(), "?"))
                + ")",
            (rs, n) -> node(rs, null),
            args.toArray());
    Map<String, Node> found = new HashMap<>();
    for (var row : rows) found.put(row.getName(), row);
    if (found.size() != names.size()) throw new RetrievalFailure("retrieval_index_incomplete", 409);
    return names.stream().map(found::get).toList();
  }

  /**
   * Ranks only graph-related candidate chunks; ties retain the upstream first-source order. @author
   * agent
   */
  public List<String> related(
      Snapshot s, String actor, List<String> candidates, float[] values, int k) {
    check(s, actor);
    if (candidates.isEmpty() || k <= 0) return List.of();
    String ids = ids(candidates), v = vector(s, values);
    int count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_index_chunk_vector WHERE knowledge_base_id=? AND"
                + " generation_id=? AND object_id=ANY(?::UUID[])",
            Integer.class,
            s.getKnowledgeBaseId(),
            s.getGenerationId(),
            ids);
    if (count != candidates.size()) throw new RetrievalFailure("retrieval_index_incomplete", 409);
    return jdbc.query(
        "SELECT object_id FROM rag_index_chunk_vector WHERE knowledge_base_id=? AND generation_id=?"
            + " AND object_id=ANY(?::UUID[]) ORDER BY embedding <=>"
            + " ?::public.vector,ARRAY_POSITION(?::UUID[],object_id) LIMIT ?",
        (rs, n) -> rs.getString("object_id"),
        s.getKnowledgeBaseId(),
        s.getGenerationId(),
        ids,
        v,
        ids,
        k);
  }

  /**
   * Resolves all source occurrences within the captured document snapshot, never the latest
   * revision. @author agent
   */
  public List<Chunk> hydrate(
      Snapshot s, String actor, List<String> ordered, Map<String, Double> scores) {
    check(s, actor);
    if (ordered.isEmpty()) return List.of();
    if (ordered.size() > 2200) throw new RetrievalFailure("retrieval_resource_limit", 413);
    String ids = ids(ordered);
    Map<String, String> content = new HashMap<>();
    jdbc.query(
        "SELECT c.id,c.content FROM rag_index_chunk ic JOIN rag_chunk c ON"
            + " c.knowledge_base_id=ic.knowledge_base_id AND c.id=ic.chunk_id WHERE"
            + " ic.knowledge_base_id=? AND ic.generation_id=? AND ic.chunk_id=ANY(?::UUID[])",
        rs -> {
          content.put(rs.getString("id"), rs.getString("content"));
        },
        s.getKnowledgeBaseId(),
        s.getGenerationId(),
        ids);
    if (content.size() != ordered.size())
      throw new RetrievalFailure("retrieval_index_incomplete", 409);
    Map<String, List<Origin>> origins = new HashMap<>();
    var rows =
        jdbc.queryForList(
            "SELECT dc.chunk_id,d.id document_id,rd.id"
                + " revision_id,d.source_name,dc.chunk_order,dc.source_start,dc.source_end,"
                + HEADINGS
                + " headings FROM rag_index_document ix JOIN rag_document d ON"
                + " d.knowledge_base_id=ix.knowledge_base_id AND d.id=ix.document_id JOIN"
                + " rag_document_revision rd ON rd.knowledge_base_id=ix.knowledge_base_id AND"
                + " rd.id=ix.revision_id AND rd.document_id=d.id JOIN rag_document_chunk dc ON"
                + " dc.knowledge_base_id=rd.knowledge_base_id AND dc.revision_id=rd.id WHERE"
                + " ix.knowledge_base_id=? AND ix.generation_id=? AND dc.chunk_id=ANY(?::UUID[])"
                + " ORDER BY d.source_name,d.id,rd.id,dc.chunk_order LIMIT ?",
            s.getKnowledgeBaseId(),
            s.getGenerationId(),
            ids,
            SOURCE_LIMIT + 1);
    if (rows.size() > SOURCE_LIMIT) throw new RetrievalFailure("retrieval_source_limit", 413);
    for (var row : rows) {
      String id = row.get("chunk_id").toString();
      origins
          .computeIfAbsent(id, key -> new ArrayList<>())
          .add(
              new Origin(
                  row.get("document_id").toString(),
                  row.get("revision_id").toString(),
                  row.get("source_name").toString(),
                  ((Number) row.get("chunk_order")).intValue(),
                  ((Number) row.get("source_start")).intValue(),
                  ((Number) row.get("source_end")).intValue(),
                  json.readValue(row.get("headings").toString(), List.class)));
    }
    List<Chunk> out = new ArrayList<>();
    for (String id : ordered) {
      var source = origins.get(id);
      if (source == null || source.isEmpty())
        throw new RetrievalFailure("retrieval_index_incomplete", 409);
      out.add(
          new Chunk(
              id,
              content.get(id),
              source.getFirst().getSourceName(),
              source.getFirst().getHeadings(),
              scores.get(id),
              List.copyOf(source)));
    }
    return out;
  }

  /**
   * Returns an exact Unicode-codepoint source excerpt after revalidating the full snapshot
   * tuple. @author agent
   */
  public Map<String, Object> source(
      UUID kb, UUID gen, UUID chunk, UUID document, UUID revision, int order, String actor) {
    if (order < 0) throw new IllegalArgumentException();
    access.requireAccess(kb, actor, false);
    var rows =
        jdbc.queryForList(
            "SELECT d.source_name,dc.source_start,dc.source_end,GREATEST(0,dc.source_start-160)"
                + " excerpt_start,SUBSTRING(rd.parsed_text FROM GREATEST(0,dc.source_start-160)+1"
                + " FOR dc.source_end-GREATEST(0,dc.source_start-160)+160)"
                + " excerpt,SUBSTRING(rd.parsed_text FROM dc.source_start+1 FOR"
                + " dc.source_end-dc.source_start) selected,c.content FROM rag_index_generation g"
                + " JOIN rag_index_document ix ON ix.knowledge_base_id=g.knowledge_base_id AND"
                + " ix.generation_id=g.id JOIN rag_document d ON"
                + " d.knowledge_base_id=ix.knowledge_base_id AND d.id=ix.document_id JOIN"
                + " rag_document_revision rd ON rd.knowledge_base_id=ix.knowledge_base_id AND"
                + " rd.id=ix.revision_id AND rd.document_id=d.id JOIN rag_document_chunk dc ON"
                + " dc.knowledge_base_id=rd.knowledge_base_id AND dc.revision_id=rd.id JOIN"
                + " rag_index_chunk ic ON ic.knowledge_base_id=g.knowledge_base_id AND"
                + " ic.generation_id=g.id AND ic.chunk_id=dc.chunk_id JOIN rag_chunk c ON"
                + " c.knowledge_base_id=ic.knowledge_base_id AND c.id=ic.chunk_id WHERE"
                + " g.knowledge_base_id=? AND g.id=? AND g.state='ready' AND dc.chunk_id=? AND"
                + " d.id=? AND rd.id=? AND dc.chunk_order=?",
            kb,
            gen,
            chunk,
            document,
            revision,
            order);
    if (rows.isEmpty()) throw new RagAccessException(404);
    var row = rows.getFirst();
    if (!Objects.equals(row.get("selected"), row.get("content")))
      throw new RetrievalFailure("retrieval_source_integrity", 409);
    return PythonJson.object(
        "sourceName",
        row.get("source_name"),
        "excerpt",
        row.get("excerpt"),
        "excerptStart",
        row.get("excerpt_start"),
        "sourceStart",
        row.get("source_start"),
        "sourceEnd",
        row.get("source_end"));
  }
}
