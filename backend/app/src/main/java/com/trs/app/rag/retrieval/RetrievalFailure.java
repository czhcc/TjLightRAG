package com.trs.app.rag.retrieval;

import lombok.Getter;

/** Safe retrieval error codes are never converted into successful empty results. @author agent */
@Getter
public class RetrievalFailure extends RuntimeException {
  private final String code;
  private final int status;

  /**
   * Creates a sanitized failure without provider, SQL, query or credential details. @author agent
   */
  public RetrievalFailure(String code, int status) {
    super(code);
    this.code = code;
    this.status = status;
  }
}
