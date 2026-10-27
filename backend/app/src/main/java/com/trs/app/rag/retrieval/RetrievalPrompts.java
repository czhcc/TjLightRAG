package com.trs.app.rag.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.*;

/**
 * Pinned templates with single-pass substitution so data cannot forge template
 * placeholders. @author agent
 */
public final class RetrievalPrompts {
  public static final String VERSION = "lightrag-aaebfe1-retrieval-v1";

  private RetrievalPrompts() {}

  /** Loads the exact upstream template bytes, preserving serialized-budget parity. @author agent */
  public static String load(String name) {
    try (var in = RetrievalPrompts.class.getResourceAsStream("/rag/retrieval/" + name)) {
      if (in == null) throw new IllegalStateException("Missing retrieval prompt");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot load retrieval prompt");
    }
  }

  /**
   * Substitutes known fields once, without interpreting document-controlled content. @author agent
   */
  public static String format(String template, Map<String, String> values) {
    Matcher matcher = Pattern.compile("\\{([a-z_]+)\\}").matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find())
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), matcher.group())));
    matcher.appendTail(out);
    return out.toString();
  }
}
