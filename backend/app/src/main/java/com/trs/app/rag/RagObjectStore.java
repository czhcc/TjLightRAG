package com.trs.app.rag;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped primitive graph/text storage, without LightRAG merge algorithms.
 *
 * @author agent
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RagObjectStore {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService access;

  /**
   * Stores a scoped text block; extraction and chunking are later phases.
   *
   * @param kb base identifier
   * @param username writer principal
   * @param id object identifier
   * @param content text
   * @author agent
   */
  public void putChunk(UUID kb, String username, UUID id, String content) {
    access.requireAccess(kb, username, true);
    if (content == null || content.isBlank()) throw new IllegalArgumentException("Empty content");
    jdbc.update(
        "INSERT INTO rag_chunk (knowledge_base_id,id,content) VALUES (?,?,?) ON CONFLICT"
            + " (knowledge_base_id,id) DO UPDATE SET content=EXCLUDED.content",
        kb,
        id,
        content);
  }

  /**
   * Stores an entity with a knowledge-base-unique normalized name.
   *
   * @param kb base identifier
   * @param username writer principal
   * @param id entity identifier
   * @param name already normalized name
   * @param type entity type
   * @param description description
   * @author agent
   */
  public void putEntity(
      UUID kb, String username, UUID id, String name, String type, String description) {
    access.requireAccess(kb, username, true);
    if (name == null || name.isBlank() || type == null || type.isBlank() || description == null)
      throw new IllegalArgumentException("Invalid entity");
    jdbc.update(
        "INSERT INTO rag_entity (knowledge_base_id,id,name,entity_type,description) VALUES"
            + " (?,?,?,?,?) ON CONFLICT (knowledge_base_id,id) DO UPDATE SET"
            + " name=EXCLUDED.name,entity_type=EXCLUDED.entity_type,description=EXCLUDED.description",
        kb,
        id,
        name,
        type,
        description);
  }

  /**
   * Stores an undirected relation with stable UUID endpoint order and scoped FKs.
   *
   * @param kb base identifier
   * @param username writer principal
   * @param id relation identifier
   * @param first first entity
   * @param second second entity
   * @param description relation description
   * @param weight finite nonnegative weight
   * @author agent
   */
  public void putRelation(
      UUID kb,
      String username,
      UUID id,
      UUID first,
      UUID second,
      String description,
      double weight) {
    access.requireAccess(kb, username, true);
    if (first == null
        || second == null
        || first.equals(second)
        || description == null
        || !Double.isFinite(weight)
        || weight < 0) throw new IllegalArgumentException("Invalid relation");
    UUID source = first.toString().compareTo(second.toString()) < 0 ? first : second,
        target = source.equals(first) ? second : first;
    jdbc.update(
        "INSERT INTO rag_relation"
            + " (knowledge_base_id,id,source_entity_id,target_entity_id,description,weight) VALUES"
            + " (?,?,?,?,?,?) ON CONFLICT (knowledge_base_id,id) DO UPDATE SET"
            + " source_entity_id=EXCLUDED.source_entity_id,target_entity_id=EXCLUDED.target_entity_id,description=EXCLUDED.description,weight=EXCLUDED.weight",
        kb,
        id,
        source,
        target,
        description,
        weight);
  }
}
