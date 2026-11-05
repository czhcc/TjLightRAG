package com.trs.app.rag.explorer;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Session-authenticated read-only object exploration endpoints. @author czhcc */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}/objects")
@RequiredArgsConstructor
public class ObjectExplorerController {
  private final ObjectExplorerService service;

  /** Returns type counts and a filtered object page in one captured generation. @author czhcc */
  @GetMapping
  public ObjectExplorerData.Listing list(@PathVariable UUID kb, Principal actor,
      @RequestParam(required = false) UUID generationId,
      @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return service.list(kb, actor.getName(), generationId, type, name, page, size);
  }

  /** Returns object properties and paged source texts and related objects. @author czhcc */
  @GetMapping("/{id}")
  public ObjectExplorerData.Detail detail(@PathVariable UUID kb, @PathVariable UUID id, Principal actor,
      @RequestParam UUID generationId, @RequestParam(defaultValue = "0") int textPage,
      @RequestParam(defaultValue = "0") int relationPage) {
    return service.detail(kb, actor.getName(), generationId, id, textPage, relationPage);
  }
}
