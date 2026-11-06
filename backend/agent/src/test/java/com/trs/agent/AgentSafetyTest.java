package com.trs.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Agent boundaries are independent of model-generated instructions. @author czhcc */
class AgentSafetyTest {
    @Test
    void namesAreLiteralAndValuesAreBound() {
        var compiled = new ObjectFilterCompiler().compile(Map.of("name", "O'Reilly_%", "types", List.of("人")), "e");
        assertFalse(compiled.sql().contains("O'Reilly"));
        assertTrue(compiled.sql().contains("POSITION"));
        assertEquals(List.of("人", "O'Reilly_%"), compiled.values());
    }

    @Test
    void rejectsUntrustedFieldsAndRecursiveFilters() {
        var compiler = new ObjectFilterCompiler();
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of("sql", "DROP TABLE app_user"), "e"));
        assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        Map.of("relatedTo", Map.of("objectFilter", Map.of("relatedTo", Map.of()))), "e"));
    }

    @Test
    void redactsSecretsRecursivelyAndNeverRetainsReasoning() {
        var redactor = new TraceProtection(Base64.getEncoder().encodeToString(new byte[32]));
        String safe = redactor.sanitize(Map.of(
                "extra",
                Map.of("api_key", "secret", "enable_thinking", false),
                "reasoning_content",
                "hidden",
                "baseUrl",
                "http://user:pass@localhost/v1?token=bad"));
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("hidden"));
        assertFalse(safe.contains("pass@"));
        assertFalse(safe.contains("bad"));
        UUID id = UUID.randomUUID();
        String cipher = redactor.encrypt(safe, id);
        assertFalse(cipher.contains("enable_thinking"));
        assertEquals(safe, redactor.decrypt(cipher, id));
        assertThrows(IllegalArgumentException.class, () -> redactor.decrypt(cipher, UUID.randomUUID()));
    }
}
