package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: DocumentView.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentView {
  private java.util.UUID id;
  private String sourceName;
  private java.util.UUID revisionId;
  private java.util.UUID jobId;
  private String format;
  private String status;
  private long sizeBytes;
  private long chunkCount;
  private java.time.Instant createdAt;
}
