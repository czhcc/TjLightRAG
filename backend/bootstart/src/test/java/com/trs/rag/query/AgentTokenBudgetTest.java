package com.trs.rag.query;

import com.trs.app.rag.agent.AgentTokenBudget;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.ModelVersion;
import com.trs.app.rag.query.QueryRequest;
import com.trs.app.rag.retrieval.RetrievalFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for per-call context allocation, independent of provider tokenization. @author czhcc */
class AgentTokenBudgetTest {
  private final AgentTokenBudget budget = new AgentTokenBudget(new RagTokenizer() {
    public String getName() { return "test-character"; }
    public int[] encode(String text) { return new int[text.length()]; }
    public byte[] tokenBytes(int token) { return new byte[0]; }
  });
  private ModelVersion model(int context, int output) {
    return new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "CHAT", "http://localhost/v1", "test",
        "encrypted", 0, context, output, 30, Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
  }
  @Test void reservesOnlyDecisionOutputAndFitsFinalOutputToRemainingContext() {
    var configured = model(12000, 11000);
    var messages = List.<Message>of(new SystemMessage("s".repeat(3000)), new UserMessage("question"));
    var decision = budget.allocate(configured, new QueryRequest(), messages, List.of(), true);
    var answer = budget.allocate(configured, new QueryRequest(), messages, List.of(), false);
    assertEquals(2048, decision.model().getMaxOutputTokens());
    assertTrue(answer.model().getMaxOutputTokens() > 2048);
    assertTrue(answer.model().getMaxOutputTokens() < 11000);
    assertEquals(answer.limit(), answer.inputTokens() + answer.model().getMaxOutputTokens() + 64);
    assertEquals(11000, configured.getMaxOutputTokens());
    assertEquals(configured.getExtraBody(), answer.model().getExtraBody());
  }
  @Test void respectsExplicitRequestBudgetAndSmallConfiguredOutputCeiling() {
    var request = new QueryRequest(); request.setMaxTotalTokens(2048);
    var allocation = budget.allocate(model(32000, 20000), request, List.of(new UserMessage("x".repeat(1000))), List.of(), false);
    assertEquals(2048, allocation.limit());
    assertTrue(allocation.model().getMaxOutputTokens() < 1024);
    assertEquals(100, budget.allocate(model(32000, 100), request, List.of(new UserMessage("x")), List.of(), true).model().getMaxOutputTokens());
  }
  @Test void rejectsInsufficientSpaceWithoutDroppingEvidenceOrChangingTheContextLimit() {
    assertThrows(RetrievalFailure.class, () -> budget.allocate(model(1024, 800), new QueryRequest(),
        List.of(new UserMessage("x".repeat(900))), List.of(), false));
  }
  @Test void countsToolResponseOnce() {
    var message = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "read_source", "x".repeat(1000)))).build();
    var allocation = budget.allocate(model(4000, 3000), new QueryRequest(), List.of(message), List.of(), true);
    assertTrue(allocation.inputTokens() >= 1000);
    assertTrue(allocation.inputTokens() < 1200, "tool content must not also be counted via getText()");
  }

  @Test void includesToolNameDescriptionAndParsedSchemaInTheInputBudget() {
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    var definition = org.mockito.Mockito.mock(org.springframework.ai.tool.definition.ToolDefinition.class);
    org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(definition);
    org.mockito.Mockito.when(definition.name()).thenReturn("read_source");
    org.mockito.Mockito.when(definition.description()).thenReturn("d".repeat(500));
    org.mockito.Mockito.when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
    var allocation = budget.allocate(model(4000, 3000), new QueryRequest(), List.of(new UserMessage("question")), List.of(callback), true);
    assertTrue(allocation.toolTokens() > 500, "name and description are part of the native tool definition");
    org.mockito.Mockito.when(definition.description()).thenReturn("");
    var shortDefinition = budget.allocate(model(4000, 3000), new QueryRequest(), List.of(new UserMessage("question")), List.of(callback), true);
    assertEquals(500, allocation.toolTokens() - shortDefinition.toolTokens());
  }
}
