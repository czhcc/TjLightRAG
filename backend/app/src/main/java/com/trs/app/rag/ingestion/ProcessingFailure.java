package com.trs.app.rag.ingestion;

import lombok.Getter;

/**
 * Safe, user-visible failure code without raw parser input or exception text.
 *
 * @author agent
 */
@Getter
public class ProcessingFailure extends RuntimeException {
  private final String code;

  /**
   * Initializes ProcessingFailure for the document pipeline.
   *
   * @author agent
   */
  public ProcessingFailure(String code) {
    super(code);
    this.code = code;
  }
}
