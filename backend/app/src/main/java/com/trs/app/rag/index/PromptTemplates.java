package com.trs.app.rag.index;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Pinned upstream templates copied with LightRAG MIT attribution. @author agent */
public final class PromptTemplates {
  private PromptTemplates() {}

  /** Loads a packaged UTF-8 template; missing provenance is a hard failure. @author agent */
  public static String load(String name) {
    try (var in = PromptTemplates.class.getResourceAsStream("/rag/prompts/" + name)) {
      if (in == null) throw new IllegalStateException("Missing pinned prompt");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot load pinned prompt");
    }
  }

  /**
   * Replaces only known template placeholders before adding untrusted source content. @author agent
   */
  public static String format(String text, Map<String, String> fields) {
    for (var f : fields.entrySet()) text = text.replace("{" + f.getKey() + "}", f.getValue());
    return text;
  }
}
