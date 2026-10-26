package com.trs.app.rag;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Membership role payload; ownership transfer is not supported.
 *
 * @author agent
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipRequest {
  @NotNull private KnowledgeRole role;
}
