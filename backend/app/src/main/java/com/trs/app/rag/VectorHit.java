package com.trs.app.rag;

import java.util.UUID;
import lombok.*;

/**
 * Scoped cosine result; similarity is one minus distance, larger is better.
 *
 * @author agent
 */
@Getter
@AllArgsConstructor
public class VectorHit {
  private final UUID objectId;
  private final double similarity;
}
