package com.trs.app.rag.agent;

import com.trs.app.rag.retrieval.RetrievalFailure;
import java.util.*;
import java.util.regex.Pattern;

/** Validates visible statistical claims before a turn can be marked complete. @author czhcc */
public final class AgentFinalizer {
  private AgentFinalizer() {}
  /** Rejects unsupported numerical claims; exact counts must be quoted in decimal digits. @author czhcc */
  public static void validateStatistics(String answer, List<Map<String, Object>> statistics) {
    if (statistics.isEmpty()) return;
    Set<String> allowed = new HashSet<>(); statistics.forEach(s -> collect(s.get("result"), allowed));
    String prose = answer.replaceAll("\\[\\d+\\]", "").replaceAll("(?m)^\\s*\\d+[.)]\\s+", "");
    var numbers = Pattern.compile("(?<![\\p{L}\\d])\\d+(?:\\.\\d+)?|(?<=[共计有为是约达])\\d+(?:\\.\\d+)?").matcher(prose);
    while (numbers.find()) if (!allowed.contains(numbers.group())) throw new RetrievalFailure("query_agent_invalid_statistics", 502);
    if (Pattern.compile("[一二三四五六七八九十百千万亿两]+\\s*(?:个|人|条|名|家|件|项|位)").matcher(prose).find()) throw new RetrievalFailure("query_agent_invalid_statistics", 502);
  }
  private static void collect(Object value, Set<String> allowed) {
    if (value instanceof Map<?, ?> map) map.forEach((k, v) -> {
      if (Set.of("total", "count", "groupTotal", "totalDistinctObjects", "value").contains(k.toString()) && v != null) allowed.add(v.toString());
      if (v instanceof Map<?, ?> || v instanceof List<?>) collect(v, allowed);
    });
    else if (value instanceof List<?> list) list.forEach(v -> collect(v, allowed));
  }
}
