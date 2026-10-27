package com.trs.app.rag.query;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Session and CSRF protected single-turn RAG generation APIs. @author agent */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}/query")
@RequiredArgsConstructor
public class QueryController {
  private final QueryService service;
  private final QueryStreams streams;

  /** Generates a bounded answer or context/prompt preview without caching. @author agent */
  @PostMapping
  public QueryResult query(
      @PathVariable UUID kb,
      @Valid @RequestBody QueryRequest request,
      Principal actor,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return service.query(kb, actor.getName(), request);
  }

  /**
   * Streams metadata, references, deltas and exactly one terminal success or error. @author agent
   */
  @PostMapping(value = "/stream", produces = "text/event-stream")
  public SseEmitter stream(
      @PathVariable UUID kb,
      @Valid @RequestBody QueryRequest request,
      Principal actor,
      HttpSession session,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Accel-Buffering", "no");
    return streams.start(kb, actor.getName(), session.getId(), request);
  }

  /**
   * Explicitly cancels a stream owned by this user and session; unknown IDs are idempotent. @author
   * agent
   */
  @PostMapping("/{id}/cancel")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void cancel(
      @PathVariable UUID kb, @PathVariable UUID id, Principal actor, HttpSession session) {
    streams.cancel(kb, actor.getName(), session.getId(), id);
  }
}
