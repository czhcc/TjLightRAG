package com.trs.app.rag;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * JPA repository with membership-scoped listing.
 *
 * @author agent
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
  /**
   * Lists only bases visible to the authenticated username.
   *
   * @param username principal name
   * @param page bounded page
   * @return visible bases
   * @author agent
   */
  @Query(
      "SELECT b FROM KnowledgeBase b JOIN KnowledgeBaseMember m ON m.knowledgeBase=b WHERE"
          + " m.user.username=:username ORDER BY b.createdAt DESC, b.id")
  List<KnowledgeBase> visibleTo(@Param("username") String username, Pageable page);
}
