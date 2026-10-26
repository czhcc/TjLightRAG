package com.trs.app.rag;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Sanitized REST errors for knowledge-base APIs.
 *
 * @author agent
 */
@RestControllerAdvice(basePackages = "com.trs.app.rag")
public class RagExceptionHandler {
  /**
   * Returns a non-disclosing access error.
   *
   * @param error scoped access failure
   * @return safe HTTP response
   * @author agent
   */
  @ExceptionHandler(RagAccessException.class)
  public ResponseEntity<Map<String, String>> access(RagAccessException error) {
    return ResponseEntity.status(error.getStatus()).body(Map.of("error", error.getMessage()));
  }

  /**
   * Rejects invalid parameters without echoing database details.
   *
   * @param error invalid input
   * @return bad request
   * @author agent
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
  }

  /**
   * Reports constraint conflicts without exposing SQL or private identifiers.
   *
   * @param error integrity failure
   * @return conflict
   * @author agent
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(409).body(Map.of("error", "Conflicting data"));
  }
}
