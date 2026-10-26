package com.trs.app.rag;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

/**
 * A user membership within one knowledge base.
 *
 * @author agent
 */
@Entity
@Table(name = "rag_knowledge_base_member")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeBaseMember {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "knowledge_base_id")
  private KnowledgeBase knowledgeBase;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id")
  private com.trs.app.UserAccount user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private KnowledgeRole role;
}
