package com.trs.app.rag;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

/**
 * Knowledge-base membership repository.
 *
 * @author agent
 */
public interface MembershipRepository extends JpaRepository<KnowledgeBaseMember, UUID> {
  /**
   * Locks a membership for the current transaction, serializing access against revocation.
   *
   * @param knowledgeBaseId base identifier
   * @param username principal name
   * @return scoped membership
   * @author agent
   */
  @Lock(LockModeType.PESSIMISTIC_READ)
  Optional<KnowledgeBaseMember> findByKnowledgeBase_IdAndUser_Username(
      UUID knowledgeBaseId, String username);
}
