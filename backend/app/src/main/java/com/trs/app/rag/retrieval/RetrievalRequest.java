package com.trs.app.rag.retrieval;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.*;

/**
 * Bounded retrieval controls; supplying keyword lists explicitly skips keyword generation. @author
 * agent
 */
@Getter
@Setter
@NoArgsConstructor
public class RetrievalRequest {
  @NotBlank
  @Size(max = 8000)
  private String query;

  @NotNull
  @Pattern(regexp = "naive|local|global|hybrid|mix")
  private String mode = "mix";

  @Min(1)
  @Max(100)
  private int topK = 20, chunkTopK = 20;

  @Min(0)
  @Max(20)
  private int relatedChunkNumber = 5;

  @NotNull
  @Pattern(regexp = "VECTOR|WEIGHT")
  private String chunkSelection = "VECTOR";

  @Min(0)
  @Max(16000)
  private int maxEntityTokens = 6000, maxRelationTokens = 8000;

  @Min(1024)
  @Max(64000)
  private int maxTotalTokens = 16000;

  @DecimalMin("-1.0")
  @DecimalMax("1.0")
  private double cosineThreshold = .2;

  @Size(max = 32)
  private List<@NotBlank @Size(max = 256) String> highLevelKeywords, lowLevelKeywords;
}
