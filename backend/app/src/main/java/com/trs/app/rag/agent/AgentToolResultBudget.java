package com.trs.app.rag.agent;

import com.trs.app.rag.ingestion.RagTokenizer;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Bounds model-visible pages while preserving exact statistics and complete retained records. @author czhcc */
@Component
@RequiredArgsConstructor
public class AgentToolResultBudget {
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();
  /** Estimates serialized data using the same tokenizer as model input planning. @author czhcc */
  public int tokens(Object value) { return tokenizer.encode(json.writeValueAsString(value)).length; }
  /** Omits whole trailing list records, never scalar totals, group keys or statistical filters. @author czhcc */
  public Object project(String tool, Object value, int maximum) {
    if (tool.startsWith("aggregate_") || tokens(value) <= maximum) return value;
    Map<String, Object> copy = new LinkedHashMap<>(json.convertValue(value, Map.class));
    var lists = new LinkedHashMap<String, List<Object>>();
    for (String key : List.of("items", "entities", "relations", "chunks"))
      if (copy.get(key) instanceof List<?> values) { var list = new ArrayList<Object>(values); lists.put(key, list); copy.put(key, list); }
    copy.put("truncated", true);
    copy.put("budgetHint", "Some complete records were omitted for context space. This is not an exhaustive list or a zero count. Use smaller pages or narrower filters.");
    var omitted = new LinkedHashMap<String, Integer>(); copy.put("omittedItems", omitted);
    while (tokens(copy) > maximum) {
      var largest = lists.entrySet().stream().filter(e -> !e.getValue().isEmpty())
          .max(Comparator.comparingInt(e -> tokens(e.getValue()))).orElseThrow(IllegalArgumentException::new);
      largest.getValue().removeLast(); omitted.merge(largest.getKey(), 1, Integer::sum);
      if (largest.getKey().equals("items") && copy.containsKey("hasMore")) copy.put("hasMore", true);
    }
    return copy;
  }
}
