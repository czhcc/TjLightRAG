package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.model.ModelExtraParameters;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Extension parameters cannot override transport or expose credentials. @author czhcc */
class ModelExtraParametersTest {
    @Test
    void copiesNestedValuesAndPreservesFalseAndSamplingParameters() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("enable_thinking", false));
        var copy = ModelExtraParameters.copy(Map.of("chat_template_kwargs", nested, "top_p", 0.8));
        nested.put("enable_thinking", true);
        assertEquals(Map.of("enable_thinking", false), copy.get("chat_template_kwargs"));
        assertThrows(UnsupportedOperationException.class, () -> copy.put("top_p", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) copy.get("chat_template_kwargs")).clear());
    }

    @Test
    void rejectsCoreOverridesSecretsUnsafeKeysAndOversizeValues() {
        for (Map<String, Object> value : List.of(
                Map.<String, Object>of("model", "another"),
                Map.<String, Object>of("messages", List.of()),
                Map.<String, Object>of("stream", false),
                Map.<String, Object>of("max_tokens", 1),
                Map.<String, Object>of("response_format", Map.of("type", "text")),
                Map.<String, Object>of("api_key", "private"),
                Map.<String, Object>of("headers", Map.of()),
                Map.<String, Object>of("chat_template_kwargs", Map.of("__proto__", true)),
                Map.<String, Object>of("large", "x".repeat(17000)),
                Map.<String, Object>of("top_p", Double.NaN))) {
            assertThrows(IllegalArgumentException.class, () -> ModelExtraParameters.copy(value));
        }
    }
}
