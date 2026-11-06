package com.trs.app.rag.index;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Collision-safe identities and typed attribute serialization within one index generation. @author czhcc */
public final class EntityIdentity {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EntityIdentity() {}

    /** Names alone never identify a new graph node. @author czhcc */
    public static String key(String name, String type) {
        return JSON.writeValueAsString(List.of(name, type));
    }

    /** Generates a stable node identifier from both normalized components. @author czhcc */
    public static UUID id(String name, String type) {
        return UUID.nameUUIDFromBytes(key(name, type).getBytes(StandardCharsets.UTF_8));
    }

    /** Retains distinct typed values without allowing one chunk to overwrite another. @author czhcc */
    public static List<Extraction.Attribute> merge(List<Extraction.Attribute> a, List<Extraction.Attribute> b) {
        Map<String, Extraction.Attribute> values = new TreeMap<>();
        for (var list : List.of(a, b))
            for (var value : list)
                values.putIfAbsent(
                        JSON.writeValueAsString(List.of(value.getName(), value.getType(), value.getValue())), value);
        return new ArrayList<>(values.values());
    }

    /** Stores typed value lists under attribute names, retaining the legacy JSON object envelope. @author czhcc */
    public static Map<String, List<Map<String, Object>>> attributes(List<Extraction.Attribute> values) {
        Map<String, List<Map<String, Object>>> result = new TreeMap<>();
        for (var value : values) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", value.getType());
            item.put("value", value.getValue());
            result.computeIfAbsent(value.getName(), k -> new ArrayList<>()).add(item);
        }
        return result;
    }

    /** Reads current typed values and historical string lists without changing old graph IDs. @author czhcc */
    public static List<Extraction.Attribute> readAttributes(String raw) {
        List<Extraction.Attribute> result = new ArrayList<>();
        var node = JSON.readTree(raw);
        for (var property : node.properties())
            for (var value : property.getValue()) {
                result.add(
                        value.isObject() && value.has("type") && value.has("value")
                                ? new Extraction.Attribute(
                                        property.getKey(), value.path("type").asText(), value.path("value"))
                                : new Extraction.Attribute(property.getKey(), "string", value));
            }
        return result;
    }
}
