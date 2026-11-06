package com.trs.rag.query;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.agent.AgentToolResultBudget;
import com.trs.app.rag.ingestion.RagTokenizer;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Large tool pages must be bounded without corrupting records or exact statistics. @author czhcc */
class AgentToolResultBudgetTest {
    private final AgentToolResultBudget budget = new AgentToolResultBudget(new RagTokenizer() {
        public String getName() {
            return "characters";
        }

        public int[] encode(String text) {
            return new int[text.length()];
        }

        public byte[] tokenBytes(int token) {
            return new byte[0];
        }
    });

    @Test
    void retainsCompleteRecordsAndMarksOmissionsWithoutChangingTheTotalOrOriginal() {
        var items = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> Map.of("id", i, "description", "x".repeat(200)))
                .toList();
        var original = Map.<String, Object>of(
                "items", items, "total", "9007199254740993", "page", 0, "size", 20, "hasMore", false);
        var result = (Map<?, ?>) budget.project("find_objects", original, 1300);
        assertEquals("9007199254740993", result.get("total"));
        assertEquals(true, result.get("truncated"));
        assertEquals(true, result.get("hasMore"));
        var kept = (List<?>) result.get("items");
        assertTrue(kept.size() > 0 && kept.size() < 20);
        assertEquals(items.subList(0, kept.size()), kept);
        assertEquals(20, items.size());
        assertTrue(budget.tokens(result) <= 1300);
    }

    @Test
    void neverTruncatesStatisticalScalarsGroupsFiltersOrEvidenceIdentifiers() {
        var result = Map.of(
                "count",
                "9007199254740993",
                "groups",
                List.of(Map.of("key", "x".repeat(2000), "count", "1")),
                "evidenceId",
                "stat-1");
        assertSame(result, budget.project("aggregate_objects", result, 512));
    }

    @Test
    void impossibleScalarPayloadRemainsAnExplicitFailure() {
        assertThrows(
                IllegalArgumentException.class,
                () -> budget.project("read_source", Map.of("excerpt", "x".repeat(3000)), 512));
    }

    @Test
    void aFullyOmittedPageDoesNotBecomeZeroMatchAnswerEvidence() {
        var run = new com.trs.app.rag.agent.AgentRunContext(
                null, "test", new com.trs.app.rag.query.QueryRequest(), null, null, System.nanoTime() + 1000000000L);
        run.getObservations()
                .add(Map.of(
                        "tool",
                        "find_objects",
                        "result",
                        Map.of("items", List.of(), "truncated", true, "total", "10")));
        assertFalse(run.hasAnswerEvidence());
        run.getObservations().add(Map.of("tool", "find_objects", "result", Map.of("items", List.of(), "total", "0")));
        assertTrue(run.hasAnswerEvidence(), "An untruncated empty result is valid evidence for that filter");
    }
}
