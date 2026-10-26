package com.trs.app.rag;

import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Validated create/update payload.
 *
 * @author agent
 */
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeBaseRequest {
  @NotBlank
  @Size(max = 100)
  private String name;

  @Size(max = 4000)
  private String description = "";
}
