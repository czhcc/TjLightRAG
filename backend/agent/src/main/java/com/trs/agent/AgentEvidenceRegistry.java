package com.trs.agent;

import java.util.*;

/** Canonical per-run citations and deduplicated evidence shared by all query tools. @author czhcc */
public class AgentEvidenceRegistry {
    private final Map<String, Map<String, Object>> references = new LinkedHashMap<>(), chunks = new LinkedHashMap<>();
    /** Assigns one stable citation to a source document revision. @author czhcc */
    public String reference(Map<String, Object> value) {
        String key = value.getOrDefault("documentId", "") + "/" + value.getOrDefault("revisionId", "") + "/"
                + value.get("file_path");
        var existing = references.get(key);
        if (existing != null) return existing.get("reference_id").toString();
        String id = Integer.toString(references.size() + 1);
        var copy = new LinkedHashMap<>(value);
        copy.put("reference_id", id);
        references.put(key, copy);
        return id;
    }
    /** Merges duplicate chunks while preserving every original source occurrence. @author czhcc */
    public void chunk(Map<String, Object> value) {
        String key = value.get("chunk_id").toString();
        var old = chunks.get(key);
        if (old == null) {
            chunks.put(key, new LinkedHashMap<>(value));
            return;
        }
        for (String field : List.of("sources", "reference_ids")) {
            Set<Object> all = new LinkedHashSet<>();
            if (old.get(field) instanceof List<?> list) all.addAll(list);
            if (value.get(field) instanceof List<?> list) all.addAll(list);
            old.put(field, List.copyOf(all));
        }
    }
    /** Returns stable source citations in assignment order. @author czhcc */
    public List<Map<String, Object>> references() {
        return List.copyOf(references.values());
    }
    /** Returns one visible evidence card per chunk. @author czhcc */
    public List<Map<String, Object>> chunks() {
        return List.copyOf(chunks.values());
    }
}
