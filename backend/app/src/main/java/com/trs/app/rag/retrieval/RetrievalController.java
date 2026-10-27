package com.trs.app.rag.retrieval;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Session/CSRF-protected retrieval data and exact revision-source read APIs. @author agent */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}/retrieval")
@RequiredArgsConstructor
public class RetrievalController {
  private final RetrievalService service;

  /** Retrieves evidence only, with no answering or reranking model invocation. @author agent */
  @PostMapping
  public RetrievalData.Result query(
      @PathVariable UUID kb, @Valid @RequestBody RetrievalRequest request, Principal user) {
    return service.query(kb, user.getName(), request);
  }

  /**
   * Reads a generation-bound Unicode excerpt after validating every source identifier. @author
   * agent
   */
  @GetMapping("/source")
  public Map<String, Object> source(
      @PathVariable UUID kb,
      @RequestParam UUID generationId,
      @RequestParam UUID chunkId,
      @RequestParam UUID documentId,
      @RequestParam UUID revisionId,
      @RequestParam int chunkOrder,
      Principal user) {
    return service.source(
        kb, generationId, chunkId, documentId, revisionId, chunkOrder, user.getName());
  }
}
