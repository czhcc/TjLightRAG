package com.trs.app.rag;

import lombok.Getter;

/**
 * Safe access failure without knowledge-base existence disclosure.
 *
 * @author agent
 */
@Getter
public class RagAccessException extends RuntimeException {
  private final int status;

  /**
   * Creates a sanitized authorization/not-found result.
   *
   * @param status HTTP status
   * @author agent
   */
  public RagAccessException(int status) {
    super(status == 403 ? "Forbidden" : "Not found");
    this.status = status;
  }
}
