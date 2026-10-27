package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: ChunkPiece.
 *
 * @author agent
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
