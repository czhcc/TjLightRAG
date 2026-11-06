package com.trs.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.ModelVersion;
import com.trs.app.rag.query.QueryRequest;
import com.trs.app.rag.retrieval.RetrievalData.Snapshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import tools.jackson.databind.json.JsonMapper;

/** Context checkpoints preserve authority, native protocol pairs and exact evidence. @author czhcc */
class AgentContextManagerTest {
    final RagTokenizer tokenizer = new RagTokenizer() {
        public String getName() {
            return "characters";
        }

        public int[] encode(String text) {
            return new int[text.length()];
        }

        public byte[] tokenBytes(int token) {
            return new byte[0];
        }
    };
    final AgentTokenBudget budget = new AgentTokenBudget(tokenizer);
    final AgentTraceRecorder traces = org.mockito.Mockito.mock(AgentTraceRecorder.class);
    final AgentContextManager manager = new AgentContextManager(tokenizer, budget, traces);
    final JsonMapper json = JsonMapper.builder().build();

    AgentRunContext run() {
        var request = new QueryRequest();
        request.setQuery("current question");
        var model = new ModelVersion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHAT",
                "http://localhost",
                "test",
                "encrypted",
                0,
                20000,
                1000,
                30);
        return new AgentRunContext(
                new Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, "test"),
                "owner",
                request,
                model,
                null,
                System.nanoTime() + 30000000000L);
    }

    List<Message> round(String id, String tool, String result) {
        return List.of(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, "{}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(id, tool, result)))
                        .build());
    }

    @Test
    void proactivelyCheckpointsOldRoundsAndKeepsNativeCallsPaired() {
        var run = run();
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("pinned instructions"),
                new UserMessage(run.getRequest().getQuery())));
        for (int i = 0; i < 4; i++)
            messages.addAll(round(
                    "call-" + i,
                    "find_objects",
                    json.writeValueAsString(Map.of(
                            "status",
                            "success",
                            "data",
                            Map.of(
                                    "items",
                                    List.of(Map.of(
                                            "id",
                                            "object-1",
                                            "name",
                                            "Alice",
                                            "type",
                                            "person",
                                            "description",
                                            "x".repeat(4200))))))));
        var prepared = manager.prepareDecision(run, messages, List.of());
        assertTrue(budget.inputTokens(prepared, List.of()) < budget.inputTokens(messages, List.of()));
        assertSame(messages.getFirst(), prepared.getFirst());
        assertTrue(prepared.stream()
                .anyMatch(m ->
                        m instanceof UserMessage && run.getRequest().getQuery().equals(m.getText())));
        Set<String> pending = new HashSet<>();
        for (var message : prepared) {
            if (message instanceof AssistantMessage a) a.getToolCalls().forEach(c -> assertTrue(pending.add(c.id())));
            if (message instanceof ToolResponseMessage t)
                t.getResponses().forEach(r -> assertTrue(pending.remove(r.id()), "orphan response"));
        }
        assertTrue(pending.isEmpty());
        assertTrue(run.getContextMemory().getCompactions() > 0);
        assertFalse(run.getContextMemory().getArchive().isEmpty());
        assertTrue(prepared.stream()
                .anyMatch(m -> m.getText() != null && m.getText().contains("object-1")));
    }

    @Test
    void memoryHandlesAreRunLocalAndReadInUnicodeSafeBoundedSegments() {
        var run = run();
        String handle = manager.archive(
                run, "get_object", Map.of("objectId", "one"), Map.of("description", "🙂证据".repeat(1000)), "success");
        var first = manager.read(run, Map.of("memoryId", handle, "maxChars", 300));
        assertEquals(handle, first.get("memoryId"));
        assertEquals(true, first.get("hasMore"));
        assertTrue(first.get("content")
                        .toString()
                        .codePointCount(0, first.get("content").toString().length())
                <= 300);
        assertThrows(IllegalArgumentException.class, () -> manager.read(run(), Map.of("memoryId", handle)));
        assertThrows(IllegalArgumentException.class, () -> manager.read(run, Map.of("memoryId", handle, "offset", -1)));
        assertTrue(first.get("content").toString().endsWith("🙂")
                || !Character.isHighSurrogate(first.get("content")
                        .toString()
                        .charAt(first.get("content").toString().length() - 1)));
    }

    @Test
    void finalContextRetainsExactStatisticsFiltersAndSourceHandles() {
        var run = run();
        var stat = Map.<String, Object>of(
                "tool",
                "aggregate_objects",
                "arguments",
                Map.of("filter", Map.of("types", List.of("person"))),
                "result",
                Map.of(
                        "total",
                        "9007199254740993",
                        "count",
                        "9007199254740993",
                        "evidenceId",
                        "stat-1",
                        "unit",
                        "extracted_object"));
        run.getStatistics().add(stat);
        run.getObservations().add(stat);
        for (int i = 0; i < 6; i++)
            run.getObservations()
                    .add(Map.of(
                            "tool",
                            "get_object_texts",
                            "arguments",
                            Map.of("objectId", "one"),
                            "result",
                            Map.of(
                                    "items",
                                    List.of(Map.of(
                                            "referenceId",
                                            "source-1",
                                            "citationId",
                                            "1",
                                            "content",
                                            "untrusted text ".repeat(900))))));
        var prepared = manager.prepareFinal(
                run,
                "pinned final instructions",
                List.of(
                        Map.of("role", "user", "content", "earlier question"),
                        Map.of("role", "assistant", "content", "earlier answer")));
        var body = prepared.toString();
        assertTrue(body.contains("9007199254740993"));
        assertTrue(body.contains("person"));
        assertTrue(body.contains("stat-1"));
        assertTrue(body.contains("source-1"));
        assertTrue(body.contains("current question"));
        assertTrue(body.contains("not current factual evidence"));
        assertTrue(body.length() < 20000);
    }

    @Test
    void smallRequestsAreLeftIntactAndPinnedInputCannotBeTruncated() {
        var run = run();
        var small = List.<Message>of(
                new SystemMessage("instructions"),
                new UserMessage(run.getRequest().getQuery()));
        assertEquals(small, manager.prepareDecision(run, small, List.of()));
        var huge = List.<Message>of(
                new SystemMessage("x".repeat(25000)),
                new UserMessage(run.getRequest().getQuery()));
        assertEquals(huge, manager.prepareDecision(run, huge, List.of()));
        assertThrows(
                RuntimeException.class,
                () -> budget.allocate(run.getAnswer(), run.getRequest(), huge, List.of(), true));
    }

    @Test
    void recallingHistoryCannotPromoteItToCurrentEvidence() {
        var run = run();
        var id = manager.archive(
                run, "conversation_history", Map.of(), Map.of("answer", "old answer"), "history_not_current_evidence");
        var recalled = manager.read(run, Map.of("memoryId", id));
        assertEquals("not_current_factual_evidence", recalled.get("authority"));
        var nested = manager.archive(run, "read_agent_context", Map.of("memoryId", id), recalled, "success");
        assertEquals(
                "not_current_factual_evidence",
                manager.read(run, Map.of("memoryId", nested)).get("authority"));
        run.getObservations().add(Map.of("tool", "read_agent_context", "result", recalled));
        assertFalse(run.hasAnswerEvidence());
    }

    @Test
    void incompleteNativeRoundsAreNeverPartiallyRemoved() {
        var run = run();
        var messages = new ArrayList<Message>(List.of(
                new SystemMessage("pinned"), new UserMessage(run.getRequest().getQuery())));
        messages.add(AssistantMessage.builder()
                .content("x".repeat(15000))
                .toolCalls(List.of(new AssistantMessage.ToolCall("pending", "function", "get_object", "{}")))
                .build());
        assertEquals(messages, manager.prepareDecision(run, messages, List.of()));
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("wrong-id", "get_object", "{}")))
                .build());
        assertEquals(messages, manager.prepareDecision(run, messages, List.of()));
    }

    @Test
    void archivedDataIsAFrozenSnapshotRatherThanALiveToolMap() {
        var run = run();
        var mutable = new HashMap<String, Object>();
        mutable.put("name", "Alice");
        var id = manager.archive(run, "get_object", Map.of(), mutable, "success");
        mutable.put("name", "Bob");
        String recalled =
                manager.read(run, Map.of("memoryId", id)).get("content").toString();
        assertTrue(recalled.contains("Alice"));
        assertFalse(recalled.contains("Bob"));
    }
}
