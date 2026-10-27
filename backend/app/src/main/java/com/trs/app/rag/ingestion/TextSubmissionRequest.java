package com.trs.app.rag.ingestion;

import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Bounded plain-text submission; bytes and cross-field window limits are checked by the service.
 *
 * @author agent
 */
@Getter
@Setter
@NoArgsConstructor
public class TextSubmissionRequest {
  @NotBlank
  @Size(max = 255)
  private String sourceName;

  @NotNull private String text;

  @NotNull
  @Pattern(regexp = "text|markdown")
  private String format = "markdown";

  @Min(16)
  @Max(4096)
  private int chunkSize = 512;

  @Min(0)
  private int overlap = 64;
}
