package com.trs.rag.model;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Complete OpenAI-compatible protocol fixtures for official SDK contract tests. @author czhcc */
public final class OpenAiFixtures {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private OpenAiFixtures() {}

  /** Adds standard metadata without changing the tested content or indices. @author czhcc */
  @SuppressWarnings("unchecked")
  public static String json(String payload) {
    Map<String, Object> root = JSON.readValue(payload, Map.class);
    root.putIfAbsent("model", "fixture-model");
    if (root.get("choices") instanceof List<?> choices) {
      root.putIfAbsent("id", "fixture-response");
      root.putIfAbsent("created", 1);
      boolean streaming = !choices.isEmpty() && ((Map<?, ?>) choices.getFirst()).containsKey("delta");
      root.putIfAbsent("object", streaming ? "chat.completion.chunk" : "chat.completion");
      for (Object item : choices) {
        Map<String, Object> choice = (Map<String, Object>) item;
        choice.putIfAbsent("index", 0);
        if (choice.get("message") instanceof Map<?, ?> message)
          ((Map<String, Object>) message).putIfAbsent("role", "assistant");
      }
    } else if (root.containsKey("data")) {
      root.putIfAbsent("object", "list");
      root.putIfAbsent("usage", Map.of("prompt_tokens", 1, "total_tokens", 1));
    }
    return JSON.writeValueAsString(root);
  }

  /** Completes JSON event envelopes while preserving deltas and DONE markers. @author czhcc */
  public static String events(String payload) {
    return payload.lines().map(line -> line.startsWith("data: ") && !line.equals("data: [DONE]")
        ? "data: " + json(line.substring(6)) : line).collect(java.util.stream.Collectors.joining("\n")) + "\n\n";
  }
}
