package com.trs.app.rag;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Immutable single-model vector-space configuration; no model calls are made.
 *
 * @author agent
 */
@Component
@Getter
public class VectorSettings {
  private final int dimensions;
  private final String model;

  /**
   * Validates settings before they become migration placeholders.
   *
   * @param dimensions vector dimension, 1..2000 for the selected HNSW vector operator class
   * @param model non-secret embedding model identity
   * @author agent
   */
  public VectorSettings(
      @Value("${rag.vector.dimensions}") int dimensions,
      @Value("${rag.vector.model}") String model) {
    if (dimensions < 1 || dimensions > 2000)
      throw new IllegalArgumentException("Vector dimensions must be 1..2000");
    if (model == null || !model.matches("[A-Za-z0-9_.:/-]{1,128}"))
      throw new IllegalArgumentException("Invalid embedding model identity");
    this.dimensions = dimensions;
    this.model = model;
  }
}
