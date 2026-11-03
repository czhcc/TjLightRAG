package com.trs.app.rag.graph;

import com.trs.app.rag.RagAccessException;
import com.trs.app.rag.graph.GraphData.*;
import com.trs.app.rag.retrieval.RetrievalData.Snapshot;
import com.trs.app.rag.retrieval.RetrievalRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Bounded graph browsing with membership and generation isolation, without model calls. @author czhcc */
@Service
@RequiredArgsConstructor
@Transactional(timeout = 15)
public class GraphService {
  private static final int PAGE = 100, SEARCH_LINKS = 200;
  private final JdbcTemplate jdbc;
  private final RetrievalRepository access;
  private final JsonMapper json = JsonMapper.builder().build();

  private Node node(ResultSet rs, int row) throws SQLException {
    return new Node(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("entity_type"),
        rs.getString("description"), json.readValue(rs.getString("attributes"), Map.class));
  }

  private Link link(ResultSet rs, int row) throws SQLException {
    return new Link(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class),
        rs.getObject("target_id", UUID.class), json.readValue(rs.getString("relation_types"), List.class),
        rs.getString("description"), rs.getDouble("weight"));
  }

  private String ids(Collection<UUID> values) {
    return "{" + String.join(",", values.stream().map(UUID::toString).toList()) + "}";
  }

  /** Lists types in the current ready index after checking knowledge-base membership. @author czhcc */
  public Types types(UUID kb, String actor) {
    var snapshot = access.capture(kb, actor);
    return new Types(snapshot.getGenerationId(), types(snapshot));
  }

  private List<String> types(Snapshot snapshot) {
    return jdbc.queryForList("SELECT DISTINCT entity_type FROM rag_index_entity WHERE knowledge_base_id=?"
        + " AND generation_id=? ORDER BY entity_type", String.class, snapshot.getKnowledgeBaseId(), snapshot.getGenerationId());
  }

  /** Searches literal case-insensitive names and exact types; only matching nodes are returned. @author czhcc */
  public Slice search(UUID kb, String actor, String type, String name) {
    if (type == null || name == null || type.length() > 100 || name.length() > 512) throw new IllegalArgumentException();
    var snapshot = access.capture(kb, actor);
    UUID gen = snapshot.getGenerationId();
    var found = jdbc.query("SELECT * FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=?"
        + " AND (?='' OR entity_type=?) AND POSITION(LOWER(?) IN LOWER(name))>0 ORDER BY name,entity_type,id LIMIT ?",
        this::node, kb, gen, type.strip(), type.strip(), name.strip(), PAGE + 1);
    boolean more = found.size() > PAGE;
    var nodes = List.copyOf(found.subList(0, Math.min(PAGE, found.size())));
    List<Link> links = List.of();
    if (!nodes.isEmpty()) {
      String selected = ids(nodes.stream().map(Node::getId).toList());
      var edges = jdbc.query("SELECT * FROM rag_index_relation WHERE knowledge_base_id=? AND generation_id=?"
          + " AND source_id=ANY(?::UUID[]) AND target_id=ANY(?::UUID[]) ORDER BY id LIMIT ?",
          this::link, kb, gen, selected, selected, SEARCH_LINKS + 1);
      more |= edges.size() > SEARCH_LINKS;
      links = List.copyOf(edges.subList(0, Math.min(SEARCH_LINKS, edges.size())));
    }
    access.check(snapshot, actor);
    return new Slice(gen, nodes, links, more, 0);
  }

  /** Expands one hop in the original generation, paging high-degree nodes by stable relation ID. @author czhcc */
  public Slice neighbors(UUID kb, String actor, UUID gen, UUID id, int offset) {
    if (offset < 0 || offset > 1000000) throw new IllegalArgumentException();
    var snapshot = new Snapshot(kb, gen, null, null, 0, "");
    access.check(snapshot, actor);
    var center = jdbc.query("SELECT * FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=? AND id=?",
        this::node, kb, gen, id);
    if (center.isEmpty()) throw new RagAccessException(404);
    var edges = jdbc.query("SELECT * FROM rag_index_relation WHERE knowledge_base_id=? AND generation_id=?"
        + " AND (source_id=? OR target_id=?) ORDER BY id LIMIT ? OFFSET ?", this::link, kb, gen, id, id, PAGE + 1, offset);
    boolean more = edges.size() > PAGE;
    var links = List.copyOf(edges.subList(0, Math.min(PAGE, edges.size())));
    Set<UUID> selected = new LinkedHashSet<>(); selected.add(id);
    for (var edge : links) { selected.add(edge.getSource()); selected.add(edge.getTarget()); }
    var nodes = jdbc.query("SELECT * FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=?"
        + " AND id=ANY(?::UUID[]) ORDER BY id", this::node, kb, gen, ids(selected));
    access.check(snapshot, actor);
    return new Slice(gen, nodes, links, more, offset + links.size());
  }
}
