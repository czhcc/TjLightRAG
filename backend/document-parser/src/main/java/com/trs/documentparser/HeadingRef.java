package com.trs.documentparser;

import lombok.*;

/**
 * Persisted ingestion contract value: HeadingRef.
 *
 * @author czhcc
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
