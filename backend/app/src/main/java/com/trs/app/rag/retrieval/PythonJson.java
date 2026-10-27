package com.trs.app.rag.retrieval;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ordered ensure-ascii-false JSON with upstream Python separators for token accounting. @author
 * agent
 */
public final class PythonJson {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PythonJson() {}

  /**
   * Serializes the exact context projection rather than a larger diagnostic object. @author agent
   */
  public static String render(Object value) {
    if (value instanceof Map<?, ?> m)
      return "{"
          + String.join(
              ", ",
              m.entrySet().stream()
                  .map(
                      e ->
                          JSON.writeValueAsString(e.getKey().toString())
                              + ": "
                              + render(e.getValue()))
                  .toList())
          + "}";
    if (value instanceof List<?> l)
      return "[" + String.join(", ", l.stream().map(PythonJson::render).toList()) + "]";
    return JSON.writeValueAsString(value);
  }

  /** Creates an insertion-ordered projection. @author agent */
  public static Map<String, Object> object(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
    return out;
  }
}
