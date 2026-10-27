package com.trs.app.rag.ingestion;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.UUID;
import lombok.*;

/**
 * Composite revision identity; every metadata lookup remains knowledge-base scoped.
 *
 * @author agent
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ScopedRevisionId implements Serializable {
  @Column(name = "knowledge_base_id")
  private UUID knowledgeBaseId;

  @Column(name = "id")
  private UUID id;
}
