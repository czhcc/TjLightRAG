package com.trs.app.rag;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parameterized pgvector access; authorization is enforced independently of REST.
 *
 * @author agent
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PgVectorStore {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;
  private final VectorSettings settings;

  /**
   * Writes a vector only for an existing object in a writable base.
   *
   * @param kb base identifier
   * @param username authenticated principal
   * @param kind whitelisted vector family
   * @param objectId scoped object identifier
   * @param embedding finite nonzero embedding
   * @author agent
   */
  public void upsert(UUID kb, String username, VectorKind kind, UUID objectId, float[] embedding) {
    access.requireAccess(kb, username, true);
    String vector = literal(embedding);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(objectId);
    jdbc.update(
        "INSERT INTO "
            + kind.getTable()
            + " (knowledge_base_id,object_id,embedding) VALUES (?,?,CAST(? AS public.vector)) ON"
            + " CONFLICT (knowledge_base_id,object_id) DO UPDATE SET"
            + " embedding=EXCLUDED.embedding,updated_at=CURRENT_TIMESTAMP",
        kb,
        objectId,
        vector);
  }

  /**
   * Exact scoped cosine baseline; materialization avoids approximate-index effects.
   *
   * @param kb base identifier
   * @param username authenticated principal
   * @param kind vector family
   * @param query finite nonzero query vector
   * @param limit result cap, 1..100
   * @param minimumSimilarity inclusive cosine threshold, -1..1
   * @return score-descending results with stable identifier tie-breaks
   * @author agent
   */
  public List<VectorHit> search(
      UUID kb,
      String username,
      VectorKind kind,
      float[] query,
      int limit,
      double minimumSimilarity) {
    access.requireAccess(kb, username, false);
    String vector = literal(query);
    Objects.requireNonNull(kind);
    if (limit < 1
        || limit > 100
        || !Double.isFinite(minimumSimilarity)
        || minimumSimilarity < -1
        || minimumSimilarity > 1) throw new IllegalArgumentException("Invalid search bounds");
    String sql =
        "WITH candidates AS MATERIALIZED (SELECT object_id,embedding FROM "
            + kind.getTable()
            + " WHERE knowledge_base_id=?) SELECT object_id,1-(embedding OPERATOR(public.<=>)"
            + " CAST(? AS public.vector)) AS similarity FROM candidates WHERE 1-(embedding"
            + " OPERATOR(public.<=>) CAST(? AS public.vector))>=? ORDER BY similarity"
            + " DESC,object_id LIMIT ?";
    return jdbc.query(
        sql,
        (rs, n) -> new VectorHit(rs.getObject("object_id", UUID.class), rs.getDouble("similarity")),
        kb,
        vector,
        vector,
        minimumSimilarity,
        limit);
  }

  private String literal(float[] values) {
    if (values == null || values.length != settings.getDimensions())
      throw new IllegalArgumentException("Wrong vector dimensions");
    double norm = 0;
    StringJoiner out = new StringJoiner(",", "[", "]");
    for (float value : values) {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite vector");
      norm += (double) value * value;
      out.add(Float.toString(value));
    }
    if (norm == 0) throw new IllegalArgumentException("Zero norm vector");
    return out.toString();
  }
}
