package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: HeadingRef.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeadingRef {
  private int start;
  private int level;
  private String text;
}
