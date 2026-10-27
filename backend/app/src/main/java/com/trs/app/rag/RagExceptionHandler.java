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
   * Rejects invalid bodies without default logging of rejected private document/query
   * fields. @author agent
   */
  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  public ResponseEntity<Map<String, String>> body(Exception failure) {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
  }

  /**
   * Reports retrieval failures without exposing the query, SQL or provider payload. @author agent
   */
  @ExceptionHandler(com.trs.app.rag.retrieval.RetrievalFailure.class)
  public ResponseEntity<Map<String, String>> retrieval(
      com.trs.app.rag.retrieval.RetrievalFailure failure) {
    return ResponseEntity.status(failure.getStatus()).body(Map.of("error", failure.getCode()));
  }

  /** Returns a sanitized model failure without provider data. @author agent */
  @ExceptionHandler(com.trs.app.rag.model.ModelFailure.class)
  public ResponseEntity<Map<String, String>> model(com.trs.app.rag.model.ModelFailure error) {
    return ResponseEntity.status(error.getCode().equals("master_key_missing") ? 409 : 502)
        .body(Map.of("error", error.getCode()));
  }

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

  /**
   * Rejects files beyond the bounded multipart limit.
   *
   * @param error oversized multipart input
   * @return payload-too-large response
   * @author agent
   */
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> uploadLimit(
      org.springframework.web.multipart.MaxUploadSizeExceededException error) {
    return ResponseEntity.status(413).body(Map.of("error", "document_too_large"));
  }
}
