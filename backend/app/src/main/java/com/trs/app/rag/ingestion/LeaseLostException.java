package com.trs.app.rag.ingestion;

/**
 * Marks a stale or cancelled worker; its writes must not be published.
 *
 * @author agent
 */
public class LeaseLostException extends RuntimeException {
  /**
   * Initializes LeaseLostException for the document pipeline.
   *
   * @author agent
   */
  public LeaseLostException() {
    super("Worker lease is no longer current");
  }
}
