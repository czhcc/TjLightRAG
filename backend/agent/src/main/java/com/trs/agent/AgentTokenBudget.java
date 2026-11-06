package com.trs.agent;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.ModelVersion;
import com.trs.app.rag.query.QueryRequest;
import com.trs.app.rag.retrieval.RetrievalFailure;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Allocates each native model request within the configured context and output ceilings. @author czhcc */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AgentTokenBudget {
    private final RagTokenizer tokenizer;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Actual per-call model settings and safe numeric diagnostics. @author czhcc */
    public record Allocation(ModelVersion model, int inputTokens, int toolTokens, int limit) {
        /** Exposes numeric budget data without prompt bodies or credentials. @author czhcc */
        public Map<String, Integer> summary() {
            return Map.of(
                    "inputTokens",
                    inputTokens,
                    "toolTokens",
                    toolTokens,
                    "outputTokens",
                    model.getMaxOutputTokens(),
                    "limit",
                    limit,
                    "safetyTokens",
                    64);
        }
    }

    /** Keeps complete tool protocol messages; only the permitted output ceiling is reduced. @author czhcc */
    public Allocation allocate(
            ModelVersion model,
            QueryRequest request,
            List<Message> messages,
            List<ToolCallback> callbacks,
            boolean decision) {
        int toolTokens = inputTokens(List.of(), callbacks) - 16;
        int input = inputTokens(messages, callbacks);
        int limit = request.hasExplicitTotalBudget()
                ? Math.min(request.getMaxTotalTokens(), model.getContextTokens())
                : model.getContextTokens();
        int available = limit - input - 64;
        int ceiling = decision ? Math.min(2048, model.getMaxOutputTokens()) : model.getMaxOutputTokens();
        if (available < Math.min(256, ceiling) || ceiling < 1) {
            log.warn(
                    "Agent context budget exhausted: decision={}, inputTokens={}, toolTokens={}, limit={}, availableOutput={}",
                    decision,
                    input,
                    toolTokens,
                    limit,
                    available);
            throw new RetrievalFailure("query_budget_too_small", 400);
        }
        var bounded = new ModelVersion(
                model.getId(),
                model.getProfileId(),
                model.getKind(),
                model.getBaseUrl(),
                model.getModel(),
                model.getCipher(),
                model.getDimensions(),
                model.getContextTokens(),
                Math.min(ceiling, available),
                model.getTimeoutSeconds(),
                model.getExtraBody());
        return new Allocation(bounded, input, toolTokens, limit);
    }
    /** Estimates input without rejecting it, so context management can act before the hard limit. @author czhcc */
    public int inputTokens(List<Message> messages, List<ToolCallback> callbacks) {
        var definitions = callbacks.stream()
                .map(c -> Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of(
                                "name",
                                c.getToolDefinition().name(),
                                "description",
                                c.getToolDefinition().description(),
                                "parameters",
                                json.readValue(c.getToolDefinition().inputSchema(), Map.class))))
                .toList();
        int toolTokens = definitions.isEmpty() ? 0 : tokenizer.encode(json.writeValueAsString(definitions)).length;
        int input = 16 + toolTokens;
        for (var message : messages) {
            if (message instanceof ToolResponseMessage tool) {
                for (var response : tool.getResponses())
                    input += 8
                            + tokenizer.encode(json.writeValueAsString(Map.of(
                                            "role",
                                            "tool",
                                            "tool_call_id",
                                            response.id(),
                                            "content",
                                            response.responseData())))
                                    .length;
            } else {
                // Match the gateway's minimum framing allowance, including native tool call arguments.
                input += 8 + tokenizer.encode(Objects.toString(message.getText(), "")).length;
                if (message instanceof AssistantMessage assistant && assistant.hasToolCalls())
                    input += tokenizer.encode(json.writeValueAsString(assistant.getToolCalls())).length;
            }
        }
        return input;
    }
}
