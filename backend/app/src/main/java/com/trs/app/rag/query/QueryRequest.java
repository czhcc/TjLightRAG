package com.trs.app.rag.query;

import com.trs.app.rag.retrieval.RetrievalRequest;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.*;

/**
 * Single-turn query; history and caller-supplied system instructions are unsupported. @author agent
 */
@Getter
@Setter
@NoArgsConstructor
public class QueryRequest extends RetrievalRequest {
  private UUID requestId;

  @NotNull
  @Pattern(regexp = "answer|context|prompt")
  private String preview = "answer";

  private boolean enableRerank = false;

  @DecimalMin("0.0")
  @DecimalMax("1.0")
  private double minRerankScore = .5;

  /**
   * Rejects unsupported history/cache/system overrides instead of silently ignoring them. @author
   * agent
   */
  @com.fasterxml.jackson.annotation.JsonAnySetter
  public void unsupported(String name, Object value) {
    throw new IllegalArgumentException("Unsupported query parameter");
  }
}
