package com.trs.app.rag;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Closed whitelist of vector tables; client input cannot become a SQL identifier.
 *
 * @author agent
 */
@Getter
@RequiredArgsConstructor
public enum VectorKind {
  CHUNK("rag_chunk_embedding"),
  ENTITY("rag_entity_embedding"),
  RELATION("rag_relation_embedding");
  private final String table;
}
