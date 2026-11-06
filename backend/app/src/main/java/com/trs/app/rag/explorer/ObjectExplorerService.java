package com.trs.app.rag.explorer;

import com.trs.app.rag.RagAccessException;
import com.trs.app.rag.explorer.ObjectExplorerData.*;
import com.trs.app.rag.graph.GraphData.Node;
import com.trs.app.rag.retrieval.RetrievalData.Snapshot;
import com.trs.app.rag.retrieval.RetrievalRepository;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Permission-scoped object lists and provenance, without retrieval or model calls. @author czhcc */
@Service
@RequiredArgsConstructor
@Transactional(timeout = 15)
public class ObjectExplorerService {
    private static final int DETAIL_SIZE = 20;
    private final JdbcTemplate jdbc;
    private final RetrievalRepository access;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String FILTER = " FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=?"
            + " AND (?='' OR entity_type=?) AND POSITION(LOWER(?) IN LOWER(name))>0";
    private static final String TEXT_FROM = " FROM rag_index_entity e"
            + " CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS_TEXT(e.source_ids) source(chunk_id)"
            + " JOIN rag_document_chunk dc ON dc.knowledge_base_id=e.knowledge_base_id AND dc.chunk_id=source.chunk_id::UUID"
            + " JOIN rag_index_document di ON di.knowledge_base_id=e.knowledge_base_id AND di.generation_id=e.generation_id AND di.revision_id=dc.revision_id"
            + " JOIN rag_document d ON d.knowledge_base_id=di.knowledge_base_id AND d.id=di.document_id"
            + " JOIN rag_chunk c ON c.knowledge_base_id=e.knowledge_base_id AND c.id=dc.chunk_id"
            + " WHERE e.knowledge_base_id=? AND e.generation_id=? AND e.id=?";
    private static final String RELATION_FROM = " FROM rag_index_relation r JOIN rag_index_entity n"
            + " ON n.knowledge_base_id=r.knowledge_base_id AND n.generation_id=r.generation_id"
            + " AND n.id=CASE WHEN r.source_id=? THEN r.target_id ELSE r.source_id END"
            + " WHERE r.knowledge_base_id=? AND r.generation_id=? AND (r.source_id=? OR r.target_id=?)";

    private Snapshot snapshot(UUID kb, String actor, UUID gen) {
        if (gen == null) return access.capture(kb, actor);
        var result = new Snapshot(kb, gen, null, null, 0, "");
        access.check(result, actor);
        return result;
    }

    private void page(int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw new IllegalArgumentException();
    }

    private Node node(ResultSet rs, int row) throws SQLException {
        return new Node(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("entity_type"),
                rs.getString("description"),
                json.readValue(rs.getString("attributes"), Map.class));
    }

    /** Lists exact type counts and a literal case-insensitive name-filtered page. @author czhcc */
    public Listing list(UUID kb, String actor, UUID generation, String type, String name, int page, int size) {
        page(page, size);
        if (type == null || name == null || type.length() > 100 || name.length() > 512)
            throw new IllegalArgumentException();
        var snapshot = snapshot(kb, actor, generation);
        UUID gen = snapshot.getGenerationId();
        var types = jdbc.query(
                "SELECT entity_type,COUNT(*) amount FROM rag_index_entity WHERE knowledge_base_id=?"
                        + " AND generation_id=? GROUP BY entity_type ORDER BY entity_type",
                (rs, row) -> new TypeCount(rs.getString("entity_type"), rs.getLong("amount")),
                kb,
                gen);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*)" + FILTER, Long.class, kb, gen, type.strip(), type.strip(), name.strip());
        var items = jdbc.query(
                "SELECT id,name,entity_type,LEFT(description,512) description,'{}'::TEXT attributes" + FILTER
                        + " ORDER BY name,entity_type,id LIMIT ? OFFSET ?",
                this::node,
                kb,
                gen,
                type.strip(),
                type.strip(),
                name.strip(),
                size,
                page * size);
        access.check(snapshot, actor);
        return new Listing(
                gen, types.stream().mapToLong(TypeCount::getCount).sum(), types, new Page<>(items, total, page, size));
    }

    /** Reads attributes, original text occurrences and one-hop neighbors in the displayed generation. @author czhcc */
    public Detail detail(UUID kb, String actor, UUID generation, UUID id, int textPage, int relationPage) {
        if (generation == null) throw new IllegalArgumentException();
        page(textPage, DETAIL_SIZE);
        page(relationPage, DETAIL_SIZE);
        var snapshot = snapshot(kb, actor, generation);
        var found = jdbc.query(
                "SELECT * FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=? AND id=?",
                this::node,
                kb,
                generation,
                id);
        if (found.isEmpty()) throw new RagAccessException(404);
        long textCount = jdbc.queryForObject("SELECT COUNT(*)" + TEXT_FROM, Long.class, kb, generation, id);
        var texts = jdbc.query(
                "SELECT dc.chunk_id,di.document_id,di.revision_id,d.source_name,dc.chunk_order,c.content" + TEXT_FROM
                        + " ORDER BY di.document_id,dc.chunk_order,dc.chunk_id LIMIT ? OFFSET ?",
                (rs, row) -> new Text(
                        rs.getObject("chunk_id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getString("source_name"),
                        rs.getInt("chunk_order"),
                        rs.getString("content")),
                kb,
                generation,
                id,
                DETAIL_SIZE,
                textPage * DETAIL_SIZE);
        long relationCount =
                jdbc.queryForObject("SELECT COUNT(*)" + RELATION_FROM, Long.class, id, kb, generation, id, id);
        var related = jdbc.query(
                "SELECT n.*,r.relation_types,r.description relation_description" + RELATION_FROM
                        + " ORDER BY n.name,n.entity_type,n.id LIMIT ? OFFSET ?",
                (rs, row) -> new Related(
                        node(rs, row),
                        json.readValue(rs.getString("relation_types"), List.class),
                        rs.getString("relation_description")),
                id,
                kb,
                generation,
                id,
                id,
                DETAIL_SIZE,
                relationPage * DETAIL_SIZE);
        access.check(snapshot, actor);
        return new Detail(
                generation,
                found.getFirst(),
                new Page<>(texts, textCount, textPage, DETAIL_SIZE),
                new Page<>(related, relationCount, relationPage, DETAIL_SIZE));
    }
}
