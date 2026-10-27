package com.trs.app.rag.ingestion;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded worker and recovery settings; publication is separate from model indexing.
 *
 * @author agent
 */
@Component
@Getter
public class IngestionSettings {
  private final int leaseSeconds, maxAttempts, concurrency, maxChunks;

  /**
   * Initializes IngestionSettings for the document pipeline.
   *
   * @author agent
   */
  public IngestionSettings(
      @Value("${rag.ingestion.lease-seconds:60}") int leaseSeconds,
      @Value("${rag.ingestion.max-attempts:3}") int maxAttempts,
      @Value("${rag.ingestion.concurrency:2}") int concurrency,
      @Value("${rag.ingestion.max-chunks:20000}") int maxChunks) {
    if (leaseSeconds < 10
        || leaseSeconds > 86400
        || maxAttempts < 1
        || maxAttempts > 10
        || concurrency < 1
        || concurrency > 8
        || maxChunks < 1
        || maxChunks > 20000) throw new IllegalArgumentException("Invalid worker bounds");
    this.leaseSeconds = leaseSeconds;
    this.maxAttempts = maxAttempts;
    this.concurrency = concurrency;
    this.maxChunks = maxChunks;
  }
}
