package com.trs.app.rag.graph;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Session-authenticated read-only graph browsing endpoints. @author czhcc */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}/graph")
@RequiredArgsConstructor
public class GraphController {
  private final GraphService service;

  /** Returns available entity types from the active index. @author czhcc */
  @GetMapping("/types")
  public GraphData.Types types(@PathVariable UUID kb, Principal actor) {
    return service.types(kb, actor.getName());
  }

  /** Searches at most one hundred entities and their internal relations. @author czhcc */
  @GetMapping
  public GraphData.Slice search(@PathVariable UUID kb, Principal actor,
      @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String name) {
    return service.search(kb, actor.getName(), type, name);
  }

  /** Returns one page of neighboring nodes within the caller's displayed generation. @author czhcc */
  @GetMapping("/entities/{id}/neighbors")
  public GraphData.Slice neighbors(@PathVariable UUID kb, @PathVariable UUID id, Principal actor,
      @RequestParam UUID generationId, @RequestParam(defaultValue = "0") int offset) {
    return service.neighbors(kb, actor.getName(), generationId, id, offset);
  }
}
