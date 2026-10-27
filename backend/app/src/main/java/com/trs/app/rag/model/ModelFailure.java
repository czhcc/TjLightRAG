package com.trs.app.rag.model;

import lombok.Getter;

/** Sanitized model and indexing failure without provider payloads. @author agent */
@Getter
public class ModelFailure extends RuntimeException {
  private final String code;

  /** Creates a safe, structured failure code. @author agent */
  public ModelFailure(String code) {
    super(code);
    this.code = code;
  }
}
