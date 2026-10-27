package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: Submission.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Submission {
  private java.util.UUID documentId;
  private java.util.UUID revisionId;
  private java.util.UUID jobId;
  private boolean duplicate;
}
