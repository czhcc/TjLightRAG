package com.trs.app.rag.index;

import java.security.Principal;
import java.util.*;
import lombok.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Knowledge-base-scoped generation construction, inspection and explicit publication. @author agent
 */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}/indexes")
@RequiredArgsConstructor
public class IndexController {
  private final IndexService service;

  /** Lists the last fifty generations. @author agent */
  @GetMapping
  public List<Map<String, Object>> list(@PathVariable UUID kb, Principal user) {
    return service.list(kb, user.getName());
  }

  /** Enqueues a snapshot build; never waits for model calls. @author agent */
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> create(@PathVariable UUID kb, Principal user) {
    return service.create(kb, user.getName());
  }

  /** Reads progress without leaking execution credentials. @author agent */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID kb, @PathVariable UUID id, Principal user) {
    return service.get(kb, id, user.getName());
  }

  /** Reads a bounded successful graph preview. @author agent */
  @GetMapping("/{id}/graph")
  public Map<String, Object> graph(@PathVariable UUID kb, @PathVariable UUID id, Principal user) {
    return service.graph(kb, id, user.getName());
  }

  /**
   * Activates a ready generation or rolls back using the same compare-and-set contract. @author
   * agent
   */
  @PostMapping("/{id}/activate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void activate(
      @PathVariable UUID kb, @PathVariable UUID id, @RequestBody Activation body, Principal user) {
    service.activate(kb, id, body.getExpectedActiveGenerationId(), user.getName());
  }

  /** Requests durable cancellation. @author agent */
  @PostMapping("/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable UUID kb, @PathVariable UUID id, Principal user) {
    service.cancel(kb, id, user.getName());
  }

  /** Resumes captured configuration and checkpoints. @author agent */
  @PostMapping("/{id}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> retry(@PathVariable UUID kb, @PathVariable UUID id, Principal user) {
    service.retry(kb, id, user.getName());
    return service.get(kb, id, user.getName());
  }

  /** Optimistic publication precondition supplied by the UI. @author agent */
  @Getter
  @Setter
  @NoArgsConstructor
  public static class Activation {
    private UUID expectedActiveGenerationId;
  }
}
