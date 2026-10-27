package com.trs.app.rag.ingestion;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Session/CSRF protected documents and durable task REST interface.
 *
 * @author agent
 */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}")
@RequiredArgsConstructor
public class DocumentController {
  private final DocumentService documents;
  private final JobService jobs;

  @PostMapping("/documents/text")
  /**
   * Accepts a CSRF-protected text submission and returns HTTP 202.
   *
   * @author agent
   */
  public ResponseEntity<Submission> text(
      @PathVariable UUID kb, Principal principal, @Valid @RequestBody TextSubmissionRequest input) {
    return ResponseEntity.accepted()
        .body(
            documents.acceptText(
                kb,
                principal.getName(),
                input.getSourceName(),
                input.getText(),
                input.getFormat(),
                input.getChunkSize(),
                input.getOverlap()));
  }

  @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  /**
   * Accepts a CSRF-protected multipart document and returns its durable task.
   *
   * @author agent
   */
  public ResponseEntity<Submission> upload(
      @PathVariable UUID kb,
      Principal principal,
      @RequestPart("file") MultipartFile file,
      @RequestParam(defaultValue = "512") int chunkSize,
      @RequestParam(defaultValue = "64") int overlap)
      throws java.io.IOException {
    return ResponseEntity.accepted()
        .body(
            documents.acceptUpload(
                kb,
                principal.getName(),
                file.getOriginalFilename(),
                file.getBytes(),
                chunkSize,
                overlap));
  }

  @GetMapping("/documents")
  /**
   * Lists permission-scoped resources with explicit pagination bounds.
   *
   * @author agent
   */
  public PageSlice<DocumentView> list(
      @PathVariable UUID kb,
      Principal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return documents.list(kb, principal.getName(), page, size);
  }

  @GetMapping("/documents/{document}")
  /**
   * Returns one resource only within the authenticated knowledge base.
   *
   * @author agent
   */
  public DocumentView get(@PathVariable UUID kb, @PathVariable UUID document, Principal principal) {
    return documents.get(kb, principal.getName(), document);
  }

  @GetMapping("/documents/{document}/chunks")
  /**
   * Returns ordered shared chunks with revision-specific source intervals.
   *
   * @author agent
   */
  public PageSlice<ChunkView> chunks(
      @PathVariable UUID kb,
      @PathVariable UUID document,
      Principal principal,
      @RequestParam(required = false) UUID revisionId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return documents.chunks(kb, principal.getName(), document, revisionId, page, size);
  }

  @GetMapping("/documents/{document}/content")
  /**
   * Returns exact parsed text or reports that parsing has not completed.
   *
   * @author agent
   */
  public ResponseEntity<String> content(
      @PathVariable UUID kb,
      @PathVariable UUID document,
      Principal principal,
      @RequestParam(required = false) UUID revisionId) {
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
        .body(documents.content(kb, principal.getName(), document, revisionId));
  }

  @GetMapping("/documents/{document}/original")
  /**
   * Returns original bytes from a verified document revision.
   *
   * @author agent
   */
  public ResponseEntity<byte[]> original(
      @PathVariable UUID kb,
      @PathVariable UUID document,
      Principal principal,
      @RequestParam(required = false) UUID revisionId) {
    var view = documents.get(kb, principal.getName(), document);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(view.getSourceName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(documents.original(kb, principal.getName(), document, revisionId));
  }

  @GetMapping("/jobs")
  /**
   * Returns the knowledge-base task page without exposing lease credentials.
   *
   * @author agent
   */
  public PageSlice<JobView> jobs(
      @PathVariable UUID kb,
      Principal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return jobs.list(kb, principal.getName(), page, size);
  }

  @GetMapping("/jobs/{job}")
  /**
   * Returns safe task details and bounded state history.
   *
   * @author agent
   */
  public JobView job(@PathVariable UUID kb, @PathVariable UUID job, Principal principal) {
    return jobs.get(kb, principal.getName(), job);
  }

  @PostMapping("/jobs/{job}/cancel")
  /**
   * Persists a cancellation request after checking write permission.
   *
   * @author agent
   */
  public ResponseEntity<JobView> cancel(
      @PathVariable UUID kb, @PathVariable UUID job, Principal principal) {
    jobs.cancel(kb, principal.getName(), job);
    return ResponseEntity.accepted().body(jobs.get(kb, principal.getName(), job));
  }

  @PostMapping("/jobs/{job}/retry")
  /**
   * Starts an explicit new attempt cycle as the authorized requesting user.
   *
   * @author agent
   */
  public ResponseEntity<JobView> retry(
      @PathVariable UUID kb, @PathVariable UUID job, Principal principal) {
    jobs.retry(kb, principal.getName(), job);
    return ResponseEntity.accepted().body(jobs.get(kb, principal.getName(), job));
  }
}
