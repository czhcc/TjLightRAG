package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: JobView.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobView {
  private java.util.UUID id;
  private java.util.UUID documentId;
  private java.util.UUID revisionId;
  private String sourceName;
  private String status;
  private int attempts;
  private int maxAttempts;
  private boolean cancelRequested;
  private String errorCode;
  private java.time.Instant createdAt;
  private java.time.Instant updatedAt;
  private java.util.List<JobEvent> events;
}
