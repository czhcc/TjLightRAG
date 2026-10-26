package com.trs.app.rag;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/**
 * Knowledge-base metadata; algorithm content remains in scoped stores.
 *
 * @author agent
 */
@Entity
@Table(name = "rag_knowledge_base")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeBase {
  @Id private UUID id;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false, columnDefinition = "text")
  private String description;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
