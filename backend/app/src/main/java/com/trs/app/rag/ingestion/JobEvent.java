package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: JobEvent.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobEvent {
  private String event;
  private String code;
  private java.time.Instant createdAt;
}
