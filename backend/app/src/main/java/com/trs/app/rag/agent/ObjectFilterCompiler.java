package com.trs.app.rag.agent;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Compiles a bounded filter language to positional SQL parameters. @author czhcc */
public class ObjectFilterCompiler {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** SQL text contains only application-owned identifiers and operators. @author czhcc */
  public record Compiled(String sql, List<Object> values) {}

  /** Compiles an object predicate; callers always add knowledge-base and generation scope. @author czhcc */
  public Compiled compile(Map<String, Object> filter, String alias) {
    if (!Set.of("e", "a", "b", "n").contains(alias)) throw new IllegalArgumentException();
    return compile(filter, alias, true);
  }

  private Compiled compile(Map<String, Object> filter, String a, boolean related) {
    allowed(filter, related ? Set.of("objectIds", "types", "name", "attributes", "relatedTo")
        : Set.of("objectIds", "types", "name", "attributes"));
    List<Object> values = new ArrayList<>(); StringBuilder sql = new StringBuilder("TRUE");
    if (filter.containsKey("objectIds")) {
      var ids = strings(filter.get("objectIds"), 100, 36);
      sql.append(" AND ").append(a).append(".id IN (").append(String.join(",", Collections.nCopies(ids.size(), "?"))).append(")");
      ids.forEach(id -> values.add(UUID.fromString(id)));
    }
    if (filter.containsKey("types")) {
      var types = strings(filter.get("types"), 20, 100);
      sql.append(" AND ").append(a).append(".entity_type IN (").append(String.join(",", Collections.nCopies(types.size(), "?"))).append(")"); values.addAll(types);
    }
    if (filter.containsKey("name")) {
      if (!(filter.get("name") instanceof String name) || name.length() > 512) throw new IllegalArgumentException();
      sql.append(" AND POSITION(LOWER(?) IN LOWER(").append(a).append(".name))>0"); values.add(name);
    }
    if (filter.containsKey("attributes")) {
      var attributes = list(filter.get("attributes"), 10);
      for (Object item : attributes) {
        var p = object(item); allowed(p, Set.of("name", "valueType", "op", "value"));
        String op = text(p.get("op"), 20), name = text(p.get("name"), 100);
        boolean exists = Set.of("EXISTS", "NOT_EXISTS").contains(op);
        if (!exists && !p.containsKey("value")) throw new IllegalArgumentException();
        if (exists && p.containsKey("value")) throw new IllegalArgumentException();
        sql.append(" AND ").append(op.equals("NOT_EXISTS") ? "NOT " : "").append("EXISTS (SELECT 1 FROM JSONB_ARRAY_ELEMENTS(CASE WHEN JSONB_TYPEOF(")
            .append(a).append(".attributes -> ? )='array' THEN ").append(a).append(".attributes -> ? ELSE '[]'::JSONB END) av(raw) WHERE TRUE");
        values.add(name); values.add(name);
        if (p.containsKey("valueType")) { sql.append(" AND av.raw ->> 'type'=?"); values.add(text(p.get("valueType"), 100)); }
        String value = "(CASE WHEN JSONB_TYPEOF(av.raw)='object' AND JSONB_EXISTS(av.raw,'value') THEN av.raw -> 'value' ELSE av.raw END)";
        if (!exists) switch (op) {
          case "EQ" -> { sql.append(" AND ").append(value).append("=?::JSONB"); values.add(JSON.writeValueAsString(p.get("value"))); }
          case "IN" -> {
            var options = list(p.get("value"), 20);
            sql.append(" AND ").append(value).append(" IN (").append(String.join(",", Collections.nCopies(options.size(), "?::JSONB"))).append(")");
            options.forEach(v -> values.add(JSON.writeValueAsString(v)));
          }
          case "CONTAINS" -> {
            sql.append(" AND JSONB_TYPEOF(").append(value).append(")='string' AND POSITION(LOWER(?) IN LOWER(").append(value).append(" #>> '{}'))>0"); values.add(text(p.get("value"), 512));
          }
          case "GT", "GTE", "LT", "LTE" -> {
            if (!(p.get("value") instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException();
            String operator = Map.of("GT", ">", "GTE", ">=", "LT", "<", "LTE", "<=").get(op);
            sql.append(" AND CASE WHEN JSONB_TYPEOF(").append(value).append(")='number' THEN (").append(value).append(" #>> '{}')::NUMERIC ").append(operator).append(" ? ELSE FALSE END"); values.add(n);
          }
          default -> throw new IllegalArgumentException();
        }
        sql.append(")");
      }
    }
    if (filter.containsKey("relatedTo")) {
      var relation = object(filter.get("relatedTo")); allowed(relation, Set.of("relationTypes", "objectFilter"));
      var target = compile(object(relation.get("objectFilter")), "n", false);
      sql.append(" AND EXISTS (SELECT 1 FROM rag_index_relation r JOIN rag_index_entity n ON n.knowledge_base_id=r.knowledge_base_id AND n.generation_id=r.generation_id AND n.id=CASE WHEN r.source_id=")
          .append(a).append(".id THEN r.target_id ELSE r.source_id END WHERE r.knowledge_base_id=").append(a).append(".knowledge_base_id AND r.generation_id=").append(a).append(".generation_id AND (r.source_id=")
          .append(a).append(".id OR r.target_id=").append(a).append(".id)");
      appendTypes(sql, values, relation.get("relationTypes"), "r");
      sql.append(" AND ").append(target.sql()).append(")"); values.addAll(target.values());
    }
    return new Compiled(sql.toString(), List.copyOf(values));
  }

  /** Adds a relation-type predicate without interpreting types as SQL. @author czhcc */
  public static void appendTypes(StringBuilder sql, List<Object> values, Object types, String alias) {
    if (types == null) return;
    var names = strings(types, 20, 100);
    sql.append(" AND EXISTS (SELECT 1 FROM JSONB_ARRAY_ELEMENTS_TEXT(").append(alias).append(".relation_types) rt(name) WHERE rt.name IN (")
        .append(String.join(",", Collections.nCopies(names.size(), "?"))).append("))"); values.addAll(names);
  }
  /** Rejects unknown properties. @author czhcc */
  public static void allowed(Map<String, Object> value, Set<String> fields) {
    if (!fields.containsAll(value.keySet())) throw new IllegalArgumentException();
  }
  /** Requires a JSON object. @author czhcc */
  @SuppressWarnings("unchecked") public static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw new IllegalArgumentException();
    return (Map<String, Object>) map;
  }
  /** Bounds collection cardinality. @author czhcc */
  public static List<?> list(Object value, int max) {
    if (!(value instanceof List<?> result) || result.isEmpty() || result.size() > max) throw new IllegalArgumentException(); return result;
  }
  /** Bounds literal string arrays. @author czhcc */
  public static List<String> strings(Object value, int max, int length) { return list(value, max).stream().map(v -> text(v, length)).toList(); }
  /** Requires a nonempty bounded literal. @author czhcc */
  public static String text(Object value, int max) {
    if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw new IllegalArgumentException(); return text;
  }
}
