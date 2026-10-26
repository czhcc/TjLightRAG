package com.trs.app.rag;

import java.util.UUID;
import lombok.*;

/**
 * Public knowledge-base view, excluding internal user and security data.
 *
 * @author agent
 */
@Getter
@AllArgsConstructor
public class KnowledgeBaseView {
  private final UUID id;
  private final String name;
  private final String description;
  private final KnowledgeRole role;
}
