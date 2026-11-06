package com.trs.agent;

import static com.trs.agent.ObjectFilterCompiler.*;

import com.trs.app.rag.retrieval.RetrievalRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, full-population SQL tools scoped to one authorized index snapshot. @author czhcc */
@Repository
@RequiredArgsConstructor
public class AgentDataRepository {
    private final JdbcTemplate jdbc;
    private final RetrievalRepository access;
    private final PlatformTransactionManager transactions;
    private final ObjectFilterCompiler compiler = new ObjectFilterCompiler();
    private final JsonMapper json = JsonMapper.builder().build();

    /** Executes bounded SQL transactions without enclosing any model operation. @author czhcc */
    public Object execute(AgentRunContext run, String tool, Map<String, Object> p) {
        run.check();
        access.check(run.getSnapshot(), run.getActor());
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(Math.min(10, Math.max(1, (int) ((run.getDeadline() - System.nanoTime()) / 1000000000L))));
        Object result = tx.execute(status -> {
            readAccess(run);
            Object value = switch (tool) {
                case "get_knowledge_schema" -> schema(run, p);
                case "find_objects" -> objects(run, p);
                case "get_object" -> detail(run, p);
                case "get_object_texts" -> texts(run, p);
                case "get_related_objects" -> related(run, p);
                case "aggregate_objects" -> aggregateObjects(run, p);
                case "aggregate_relations" -> aggregateRelations(run, p);
                default -> throw new IllegalArgumentException();
            };
            run.check();
            readAccess(run);
            return value;
        });
        access.check(run.getSnapshot(), run.getActor());
        return result;
    }

    private void readAccess(AgentRunContext run) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM rag_knowledge_base_member m JOIN app_user u ON u.id=m.user_id JOIN rag_index_generation g ON g.knowledge_base_id=m.knowledge_base_id WHERE m.knowledge_base_id=? AND g.id=? AND g.state='ready' AND u.username=?)",
                Boolean.class,
                run.getSnapshot().getKnowledgeBaseId(),
                run.getSnapshot().getGenerationId(),
                run.getActor()))) throw new com.trs.app.rag.RagAccessException(404);
    }

    private List<Object> scope(AgentRunContext c) {
        return new ArrayList<>(
                List.of(c.getSnapshot().getKnowledgeBaseId(), c.getSnapshot().getGenerationId()));
    }

    private static int number(Map<String, Object> p, String key, int fallback, int min, int max) {
        Object v = p.getOrDefault(key, fallback);
        if (!(v instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max)
            throw new IllegalArgumentException();
        return n.intValue();
    }

    private static UUID id(Map<String, Object> p) {
        return UUID.fromString(text(p.get("objectId"), 36));
    }

    private List<Map<String, Object>> rows(String sql, List<Object> args) {
        return jdbc.query(
                sql,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var metadata = rs.getMetaData();
                    for (int i = 1; i <= metadata.getColumnCount(); i++)
                        row.put(
                                metadata.getColumnLabel(i),
                                metadata.getColumnTypeName(i).equals("jsonb")
                                        ? json.readValue(rs.getString(i), Object.class)
                                        : rs.getObject(i));
                    return row;
                },
                args.toArray());
    }

    private long count(String sql, List<Object> args) {
        return jdbc.queryForObject(sql, Long.class, args.toArray());
    }

    private Map<String, Object> page(
            String from, String projection, String order, List<Object> args, Map<String, Object> p) {
        int page = number(p, "page", 0, 0, 1000000), size = number(p, "size", 20, 1, 100);
        String total = Long.toString(count("SELECT COUNT(*) " + from, args));
        List<Object> paged = new ArrayList<>(args);
        paged.add(size);
        paged.add((long) page * size);
        return Map.of(
                "items",
                rows("SELECT " + projection + " " + from + " ORDER BY " + order + " LIMIT ? OFFSET ?", paged),
                "total",
                total,
                "page",
                page,
                "size",
                size,
                "hasMore",
                ((long) page + 1) * size < Long.parseLong(total));
    }

    private Object objects(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("filter", "page", "size"));
        var filter = compiler.compile(object(p.getOrDefault("filter", Map.of())), "e");
        var args = scope(c);
        args.addAll(filter.values());
        return page(
                "FROM rag_index_entity e WHERE e.knowledge_base_id=? AND e.generation_id=? AND " + filter.sql(),
                "e.id,e.name,e.entity_type AS type,LEFT(e.description,512) AS description",
                "e.name,e.entity_type,e.id",
                args,
                p);
    }

    private Object detail(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("objectId"));
        var args = scope(c);
        args.add(id(p));
        var found = rows(
                "SELECT id,name,entity_type AS type,description,attributes FROM rag_index_entity WHERE knowledge_base_id=? AND generation_id=? AND id=?",
                args);
        return Map.of("items", found);
    }

    private Object texts(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("objectId", "page", "size"));
        var args = scope(c);
        args.add(id(p));
        String from = "FROM rag_index_entity e CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS_TEXT(e.source_ids) s(chunk_id)"
                + " JOIN rag_document_chunk dc ON dc.knowledge_base_id=e.knowledge_base_id AND dc.chunk_id=s.chunk_id::UUID"
                + " JOIN rag_index_document di ON di.knowledge_base_id=e.knowledge_base_id AND di.generation_id=e.generation_id AND di.revision_id=dc.revision_id"
                + " JOIN rag_document d ON d.knowledge_base_id=di.knowledge_base_id AND d.id=di.document_id"
                + " JOIN rag_chunk ch ON ch.knowledge_base_id=e.knowledge_base_id AND ch.id=dc.chunk_id WHERE e.knowledge_base_id=? AND e.generation_id=? AND e.id=?";
        var result = page(
                from,
                "dc.chunk_id AS \"chunkId\",di.document_id AS \"documentId\",di.revision_id AS \"revisionId\",d.source_name AS \"sourceName\",dc.chunk_order AS \"chunkOrder\",LEFT(ch.content,4000) AS content",
                "di.document_id,dc.chunk_order,dc.chunk_id",
                args,
                p);
        for (var item : (List<Map<String, Object>>) result.get("items")) {
            item.put(
                    "referenceId",
                    c.source(Map.of(
                            "chunkId",
                            item.get("chunkId"),
                            "documentId",
                            item.get("documentId"),
                            "revisionId",
                            item.get("revisionId"),
                            "chunkOrder",
                            item.get("chunkOrder"))));
            String citation = c.getEvidence()
                    .reference(Map.of(
                            "file_path",
                            item.get("sourceName"),
                            "documentId",
                            item.get("documentId"),
                            "revisionId",
                            item.get("revisionId")));
            item.put("citationId", citation);
            var origin = Map.of(
                    "sourceName",
                    item.get("sourceName"),
                    "documentId",
                    item.get("documentId"),
                    "revisionId",
                    item.get("revisionId"),
                    "chunkOrder",
                    item.get("chunkOrder"));
            c.getEvidence()
                    .chunk(Map.of(
                            "chunk_id",
                            item.get("chunkId"),
                            "content",
                            item.get("content"),
                            "file_path",
                            item.get("sourceName"),
                            "reference_id",
                            citation,
                            "reference_ids",
                            List.of(citation),
                            "sources",
                            List.of(origin)));
        }
        return result;
    }

    private Object related(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("objectId", "relationTypes", "targetFilter", "page", "size"));
        UUID id = id(p);
        var args = new ArrayList<Object>(List.of(id));
        args.addAll(scope(c));
        args.add(id);
        args.add(id);
        var sql = new StringBuilder(
                "FROM rag_index_relation r JOIN rag_index_entity n ON n.knowledge_base_id=r.knowledge_base_id AND n.generation_id=r.generation_id AND n.id=CASE WHEN r.source_id=? THEN r.target_id ELSE r.source_id END WHERE r.knowledge_base_id=? AND r.generation_id=? AND (r.source_id=? OR r.target_id=?)");
        appendTypes(sql, args, p.get("relationTypes"), "r");
        var target = object(p.getOrDefault("targetFilter", Map.of()));
        if (target.containsKey("relatedTo")) throw new IllegalArgumentException();
        var compiled = compiler.compile(target, "n");
        sql.append(" AND ").append(compiled.sql());
        args.addAll(compiled.values());
        return page(
                sql.toString(),
                "n.id,n.name,n.entity_type AS type,r.id AS \"relationId\",r.relation_types AS \"relationTypes\",LEFT(r.description,1000) AS description",
                "n.name,n.entity_type,n.id",
                args,
                p);
    }

    private Object schema(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("section", "entityTypes", "page", "size"));
        String section = text(p.get("section"), 30);
        var args = scope(c);
        if (p.containsKey("entityTypes") && !section.equals("ATTRIBUTES")) throw new IllegalArgumentException();
        String scope = "e.knowledge_base_id=? AND e.generation_id=?";
        if (p.containsKey("entityTypes")) {
            var types = strings(p.get("entityTypes"), 20, 100);
            scope += " AND e.entity_type IN (" + String.join(",", Collections.nCopies(types.size(), "?")) + ")";
            args.addAll(types);
        }
        String query = switch (section) {
            case "ENTITY_TYPES" ->
                "SELECT e.entity_type AS name,COUNT(*)::TEXT AS count FROM rag_index_entity e WHERE " + scope
                        + " GROUP BY e.entity_type";
            case "ATTRIBUTES" ->
                "SELECT e.entity_type AS \"entityType\",k.name,COALESCE(v.raw ->> 'type','legacy') AS \"valueType\",JSONB_TYPEOF(CASE WHEN JSONB_TYPEOF(v.raw)='object' AND JSONB_EXISTS(v.raw,'value') THEN v.raw -> 'value' ELSE v.raw END) AS \"jsonValueType\",COUNT(DISTINCT e.id)::TEXT AS count FROM rag_index_entity e CROSS JOIN LATERAL JSONB_EACH(e.attributes) k(name,attribute_values) CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS(CASE WHEN JSONB_TYPEOF(k.attribute_values)='array' THEN k.attribute_values ELSE '[]'::JSONB END) v(raw) WHERE "
                        + scope + " GROUP BY e.entity_type,k.name,\"valueType\",\"jsonValueType\"";
            case "RELATION_TYPES" -> {
                if (p.containsKey("entityTypes")) throw new IllegalArgumentException();
                yield "SELECT t.name,COUNT(DISTINCT e.id)::TEXT AS count FROM rag_index_relation e CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS_TEXT(e.relation_types) t(name) WHERE "
                        + scope + " GROUP BY t.name";
            }
            default -> throw new IllegalArgumentException();
        };
        var paging = new LinkedHashMap<>(p);
        paging.putIfAbsent("size", 50);
        return page("FROM (" + query + ") schema", "*", "name,ROW_TO_JSON(schema)::TEXT", args, paging);
    }

    private Object aggregateObjects(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("filter", "groupBy", "groupPage", "groupSize", "order"));
        var f = compiler.compile(object(p.getOrDefault("filter", Map.of())), "e");
        var args = scope(c);
        args.addAll(f.values());
        String base =
                "SELECT e.* FROM rag_index_entity e WHERE e.knowledge_base_id=? AND e.generation_id=? AND " + f.sql();
        var group = object(p.getOrDefault("groupBy", Map.of("kind", "NONE")));
        allowed(group, Set.of("kind", "attributeName", "relationTypes", "targetFilter", "includeMissing"));
        String kind = text(group.get("kind"), 30);
        allowed(
                group,
                switch (kind) {
                    case "NONE", "TYPE" -> Set.of("kind");
                    case "ATTRIBUTE" -> Set.of("kind", "attributeName", "includeMissing");
                    case "RELATED_OBJECT" -> Set.of("kind", "relationTypes", "targetFilter", "includeMissing");
                    default -> throw new IllegalArgumentException();
                });
        if (group.containsKey("includeMissing") && !(group.get("includeMissing") instanceof Boolean))
            throw new IllegalArgumentException();
        String total = Long.toString(count("SELECT COUNT(*) FROM (" + base + ") selected", args));
        String grouped;
        switch (kind) {
            case "NONE" -> {
                return Map.of(
                        "total",
                        total,
                        "count",
                        total,
                        "filter",
                        p.getOrDefault("filter", Map.of()),
                        "groupBy",
                        group,
                        "groups",
                        List.of(),
                        "groupTotal",
                        "0",
                        "basis",
                        "COUNT DISTINCT object id in captured index generation");
            }
            case "TYPE" ->
                grouped =
                        "SELECT JSONB_BUILD_OBJECT('type',s.entity_type) AS key,COUNT(DISTINCT s.id)::TEXT AS count FROM selected s GROUP BY s.entity_type";
            case "ATTRIBUTE" -> {
                String name = text(group.get("attributeName"), 100);
                var scalarArgs = new ArrayList<>(args);
                scalarArgs.add(name);
                scalarArgs.add(name);
                if (count(
                                "WITH selected AS (" + base
                                        + ") SELECT COUNT(*) FROM selected s CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS(CASE WHEN JSONB_TYPEOF(s.attributes -> ?)='array' THEN s.attributes -> ? ELSE '[]'::JSONB END) v(raw) WHERE JSONB_TYPEOF(CASE WHEN JSONB_TYPEOF(v.raw)='object' AND JSONB_EXISTS(v.raw,'value') THEN v.raw -> 'value' ELSE v.raw END) IN ('object','array')",
                                scalarArgs)
                        > 0) throw new IllegalArgumentException();
                args.add(name);
                grouped =
                        "SELECT JSONB_BUILD_OBJECT('valueType',v.raw -> 'type','value',CASE WHEN JSONB_TYPEOF(v.raw)='object' AND JSONB_EXISTS(v.raw,'value') THEN v.raw -> 'value' ELSE v.raw END,'missing',v.raw IS NULL) AS key,COUNT(DISTINCT s.id)::TEXT AS count FROM selected s LEFT JOIN LATERAL JSONB_ARRAY_ELEMENTS(CASE WHEN JSONB_TYPEOF(s.attributes -> ?)='array' THEN s.attributes -> ? ELSE '[]'::JSONB END) v(raw) ON TRUE";
                args.add(name);
                grouped += Boolean.FALSE.equals(group.get("includeMissing")) ? " WHERE v.raw IS NOT NULL" : "";
                grouped += " GROUP BY key";
            }
            case "RELATED_OBJECT" -> {
                var target = object(group.getOrDefault("targetFilter", Map.of()));
                if (target.containsKey("relatedTo")) throw new IllegalArgumentException();
                var tf = compiler.compile(target, "n");
                var condition = new StringBuilder("TRUE");
                var rv = new ArrayList<Object>();
                appendTypes(condition, rv, group.get("relationTypes"), "r");
                grouped =
                        "SELECT JSONB_BUILD_OBJECT('objectId',n.id,'name',n.name,'type',n.entity_type,'missing',n.id IS NULL) AS key,COUNT(DISTINCT s.id)::TEXT AS count FROM selected s LEFT JOIN LATERAL (SELECT DISTINCT n.id,n.name,n.entity_type FROM rag_index_relation r JOIN rag_index_entity n ON n.knowledge_base_id=r.knowledge_base_id AND n.generation_id=r.generation_id AND n.id=CASE WHEN r.source_id=s.id THEN r.target_id ELSE r.source_id END WHERE r.knowledge_base_id=s.knowledge_base_id AND r.generation_id=s.generation_id AND (r.source_id=s.id OR r.target_id=s.id) AND "
                                + condition + " AND " + tf.sql() + ") n ON TRUE";
                args.addAll(rv);
                args.addAll(tf.values());
                if (Boolean.FALSE.equals(group.get("includeMissing"))) grouped += " WHERE n.id IS NOT NULL";
                grouped += " GROUP BY n.id,n.name,n.entity_type";
            }
            default -> throw new IllegalArgumentException();
        }
        return grouped(base, grouped, args, p, total);
    }

    private Object aggregateRelations(AgentRunContext c, Map<String, Object> p) {
        allowed(p, Set.of("relationTypes", "endpointA", "endpointB", "groupBy", "groupPage", "groupSize", "order"));
        var fa = object(p.getOrDefault("endpointA", Map.of()));
        var fb = object(p.getOrDefault("endpointB", Map.of()));
        if (fa.containsKey("relatedTo") || fb.containsKey("relatedTo")) throw new IllegalArgumentException();
        var a = compiler.compile(fa, "a");
        var b = compiler.compile(fb, "b");
        var ar = compiler.compile(fa, "b");
        var br = compiler.compile(fb, "a");
        var args = scope(c);
        StringBuilder where = new StringBuilder("r.knowledge_base_id=? AND r.generation_id=?");
        appendTypes(where, args, p.get("relationTypes"), "r");
        where.append(" AND ((")
                .append(a.sql())
                .append(" AND ")
                .append(b.sql())
                .append(") OR (")
                .append(ar.sql())
                .append(" AND ")
                .append(br.sql())
                .append("))");
        args.addAll(a.values());
        args.addAll(b.values());
        args.addAll(ar.values());
        args.addAll(br.values());
        String base =
                "SELECT r.* FROM rag_index_relation r JOIN rag_index_entity a ON a.knowledge_base_id=r.knowledge_base_id AND a.generation_id=r.generation_id AND a.id=r.source_id JOIN rag_index_entity b ON b.knowledge_base_id=r.knowledge_base_id AND b.generation_id=r.generation_id AND b.id=r.target_id WHERE "
                        + where;
        String total = Long.toString(count("SELECT COUNT(*) FROM (" + base + ") r", args));
        Object grouping = p.getOrDefault("groupBy", "NONE");
        if (!(grouping instanceof String)) allowed(object(grouping), Set.of("kind"));
        String group = grouping instanceof String s ? s : text(object(grouping).get("kind"), 30);
        if (group.equals("NONE"))
            return Map.of(
                    "total",
                    total,
                    "count",
                    total,
                    "groups",
                    List.of(),
                    "groupTotal",
                    "0",
                    "basis",
                    "COUNT DISTINCT undirected relation id, not weight");
        if (!group.equals("RELATION_TYPE")) throw new IllegalArgumentException();
        String grouped =
                "SELECT JSONB_BUILD_OBJECT('relationType',t.name) AS key,COUNT(DISTINCT s.id)::TEXT AS count FROM selected s LEFT JOIN LATERAL JSONB_ARRAY_ELEMENTS_TEXT(s.relation_types) t(name) ON TRUE";
        if (p.containsKey("relationTypes")) {
            var names = strings(p.get("relationTypes"), 20, 100);
            grouped += " WHERE t.name IN (" + String.join(",", Collections.nCopies(names.size(), "?")) + ")";
            args.addAll(names);
        }
        return grouped(base, grouped + " GROUP BY t.name", args, p, total);
    }

    private Object grouped(String base, String grouped, List<Object> args, Map<String, Object> p, String total) {
        String prefix = "WITH selected AS (" + base + "), grouped AS (" + grouped + ") ";
        String order = switch (Objects.toString(p.getOrDefault("order", "COUNT_DESC"))) {
            case "COUNT_DESC" -> "count::BIGINT DESC,key::TEXT";
            case "COUNT_ASC" -> "count::BIGINT,key::TEXT";
            case "KEY_ASC" -> "key::TEXT";
            default -> throw new IllegalArgumentException();
        };
        int page = number(p, "groupPage", 0, 0, 1000000), size = number(p, "groupSize", 20, 1, 100);
        String groups = Long.toString(count(prefix + "SELECT COUNT(*) FROM grouped", args));
        var paged = new ArrayList<>(args);
        paged.add(size);
        paged.add((long) page * size);
        return Map.of(
                "total",
                total,
                "totalDistinctObjects",
                total,
                "groupTotal",
                groups,
                "groups",
                rows(prefix + "SELECT * FROM grouped ORDER BY " + order + " LIMIT ? OFFSET ?", paged),
                "groupPage",
                page,
                "groupSize",
                size,
                "filter",
                p,
                "basis",
                "Full-population distinct count; one object may occur in multiple groups");
    }
}
