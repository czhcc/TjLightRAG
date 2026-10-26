package com.trs.rag.baseline;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Mutation checks ensure future Java candidates cannot pass by ignoring ordering or fields.
 * @author agent
 */
class GoldenAssertionsTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void rejectsReorderedCandidates() {
        assertThrows(AssertionError.class, () -> GoldenAssertions.assertEquivalent(json.readTree("[1,2]"), json.readTree("[2,1]"), 1e-8));
    }
    @Test void rejectsMissingProvenance() {
        assertThrows(AssertionError.class, () -> GoldenAssertions.assertEquivalent(json.readTree("{\"source_id\":\"c1\"}"), json.readTree("{}"), 1e-8));
    }
    @Test void allowsOnlyDeclaredNumericTolerance() {
        GoldenAssertions.assertEquivalent(json.readTree("0.5"), json.readTree("0.500000001"), 1e-8);
        assertThrows(AssertionError.class, () -> GoldenAssertions.assertEquivalent(json.readTree("0.5"), json.readTree("0.51"), 1e-8));
    }
    @Test void comparesErrorsAndNullStrictly() {
        assertThrows(AssertionError.class, () -> GoldenAssertions.assertEquivalent(json.readTree("{\"error\":\"RuntimeError\"}"), json.readTree("[]"), 1e-8));
        assertThrows(AssertionError.class, () -> GoldenAssertions.assertEquivalent(json.readTree("null"), json.readTree("\"\""), 1e-8));
    }
}
