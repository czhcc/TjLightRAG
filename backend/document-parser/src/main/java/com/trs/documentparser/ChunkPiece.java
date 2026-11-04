package com.trs.documentparser;

import lombok.*;

/**
 * Persisted ingestion contract value: ChunkPiece.
 *
 * @author czhcc
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPiece {
  private String content;
  private int tokens;
  private int tokenStart;
  private int tokenEnd;
  private int sourceStart;
  private int sourceEnd;
  private java.util.List<String> headings;
}
