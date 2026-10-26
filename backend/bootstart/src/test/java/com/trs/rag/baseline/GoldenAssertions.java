package com.trs.rag.baseline;

import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Shared comparison boundary for future production-algorithm adapters.
 * No reference output is used as a candidate implementation.
 * @author agent
 */
public final class GoldenAssertions {
    private GoldenAssertions() { }

    /** Compares complete JSON trees, keeping array order and all provenance fields.
     * Only numeric leaves may use the explicitly supplied absolute tolerance.
     * @param expected pinned upstream result
     * @param actual candidate Java result
     * @param tolerance finite nonnegative absolute numeric tolerance
     * @author agent
     */
    public static void assertEquivalent(JsonNode expected, JsonNode actual, double tolerance) {
        assertTrue(Double.isFinite(tolerance) && tolerance >= 0, "Invalid tolerance");
        compare(expected, actual, tolerance, "$");
    }

    private static void compare(JsonNode expected, JsonNode actual, double tolerance, String path) {
        assertNotNull(actual, path + ": missing candidate result");
        if (expected.isNumber() && actual.isNumber()) {
            if (expected.isIntegralNumber()) {
                assertTrue(actual.isIntegralNumber(), path + ": integer required");
                assertEquals(expected.bigIntegerValue(), actual.bigIntegerValue(), path);
            } else {
                assertTrue(Double.isFinite(actual.asDouble()), path + ": nonfinite number");
                assertEquals(expected.asDouble(), actual.asDouble(), tolerance, path);
            }
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), path + ": array required");
            assertEquals(expected.size(), actual.size(), path + ": array length");
            for (int i = 0; i < expected.size(); i++) compare(expected.get(i), actual.get(i), tolerance, path + "[" + i + "]");
        } else if (expected.isObject()) {
            assertTrue(actual.isObject(), path + ": object required");
            assertEquals(expected.size(), actual.size(), path + ": field count");
            for (var field : expected.properties()) compare(field.getValue(), actual.get(field.getKey()), tolerance, path + "." + field.getKey());
        } else {
            assertEquals(expected, actual, path);
        }
    }
}
