package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: JobLease.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobLease {
  private java.util.UUID id;
  private java.util.UUID kbId;
  private java.util.UUID revisionId;
  private java.util.UUID token;
  private String actor;
  private int attempts;
}
