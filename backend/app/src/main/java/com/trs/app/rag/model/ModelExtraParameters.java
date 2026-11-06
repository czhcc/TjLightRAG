package com.trs.app.rag.model;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Bounded immutable JSON extensions that cannot replace managed model settings. @author czhcc */
public final class ModelExtraParameters {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> MANAGED = Set.of(
            "model",
            "messages",
            "stream",
            "stream_options",
            "max_tokens",
            "max_completion_tokens",
            "temperature",
            "base_url",
            "baseurl",
            "timeout",
            "headers",
            "custom_headers",
            "tools",
            "tool_choice",
            "n",
            "extra_body",
            "response_format");
    private static final Set<String> UNSAFE = Set.of(
            "__proto__",
            "prototype",
            "constructor",
            "api_key",
            "apikey",
            "authorization",
            "password",
            "secret",
            "access_token");

    private ModelExtraParameters() {}

    /** Validates and deeply copies JSON while preserving false, numbers, arrays and null. @author czhcc */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> copy(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, Object> result = (Map<String, Object>) value(input, 0, new int[] {0});
        if (JSON.writeValueAsString(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) invalid();
        return result;
    }

    /** Reads the version snapshot without exposing raw JSON on validation failure. @author czhcc */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String json) {
        try {
            return copy(JSON.readValue(json, Map.class));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid model extension parameters");
        }
    }

    private static Object value(Object input, int depth, int[] count) {
        if (depth > 8 || ++count[0] > 1024) invalid();
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) invalid();
                String key = (String) entry.getKey();
                String lower = key.toLowerCase(Locale.ROOT);
                if (key.isBlank()
                        || key.length() > 100
                        || UNSAFE.contains(lower)
                        || depth == 0 && MANAGED.contains(lower)) invalid();
                result.put(key, value(entry.getValue(), depth + 1, count));
            }
            return Collections.unmodifiableMap(result);
        }
        if (input instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object element : list) result.add(value(element, depth + 1, count));
            return Collections.unmodifiableList(result);
        }
        if (input == null || input instanceof Boolean) return input;
        if (input instanceof String text && text.length() <= 16384) return input;
        if ((input instanceof Byte
                        || input instanceof Short
                        || input instanceof Integer
                        || input instanceof Long
                        || input instanceof Float
                        || input instanceof Double
                        || input instanceof java.math.BigInteger
                        || input instanceof java.math.BigDecimal)
                && Double.isFinite(((Number) input).doubleValue())) return input;
        invalid();
        return null;
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid model extension parameters");
    }
}
