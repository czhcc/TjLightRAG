package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: DocumentInput.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentInput {
  private byte[] original;
  private String format;
  private int chunkSize;
  private int overlap;
  private String parsedText;
}
