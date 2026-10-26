package com.trs.app.rag;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Session-authenticated knowledge-base REST endpoints.
 *
 * @author agent
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {
  private final KnowledgeBaseService service;

  /**
   * Lists bases visible to the session principal.
   *
   * @param principal session principal
   * @param page zero-based page
   * @param size page size
   * @return visible bases
   * @author agent
   */
  @GetMapping
  public List<KnowledgeBaseView> list(
      Principal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(principal.getName(), page, size);
  }

  /**
   * Creates a base for the session principal.
   *
   * @param principal session principal
   * @param request validated payload
   * @return created base with location
   * @author agent
   */
  @PostMapping
  public ResponseEntity<KnowledgeBaseView> create(
      Principal principal, @Valid @RequestBody KnowledgeBaseRequest request) {
    var result = service.create(principal.getName(), request.getName(), request.getDescription());
    return ResponseEntity.created(URI.create("/api/knowledge-bases/" + result.getId()))
        .body(result);
  }

  /**
   * Reads a visible base.
   *
   * @param id base identifier
   * @param principal session principal
   * @return base view
   * @author agent
   */
  @GetMapping("/{id}")
  public KnowledgeBaseView get(@PathVariable UUID id, Principal principal) {
    return service.get(id, principal.getName());
  }

  /**
   * Updates metadata as owner.
   *
   * @param id base identifier
   * @param principal session principal
   * @param request metadata
   * @return updated view
   * @author agent
   */
  @PutMapping("/{id}")
  public KnowledgeBaseView update(
      @PathVariable UUID id,
      Principal principal,
      @Valid @RequestBody KnowledgeBaseRequest request) {
    return service.update(id, principal.getName(), request.getName(), request.getDescription());
  }

  /**
   * Deletes a base as owner.
   *
   * @param id base identifier
   * @param principal session principal
   * @return no content
   * @author agent
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, Principal principal) {
    service.delete(id, principal.getName());
    return ResponseEntity.noContent().build();
  }

  /**
   * Grants reader or writer access to an existing user.
   *
   * @param id base identifier
   * @param username target username
   * @param principal owner principal
   * @param request requested role
   * @return no content
   * @author agent
   */
  @PutMapping("/{id}/members/{username}")
  public ResponseEntity<Void> grant(
      @PathVariable UUID id,
      @PathVariable String username,
      Principal principal,
      @Valid @RequestBody MembershipRequest request) {
    service.grant(id, principal.getName(), username, request.getRole());
    return ResponseEntity.noContent().build();
  }

  /**
   * Revokes a non-owner membership.
   *
   * @param id base identifier
   * @param username target username
   * @param principal owner principal
   * @return no content
   * @author agent
   */
  @DeleteMapping("/{id}/members/{username}")
  public ResponseEntity<Void> revoke(
      @PathVariable UUID id, @PathVariable String username, Principal principal) {
    service.revoke(id, principal.getName(), username);
    return ResponseEntity.noContent().build();
  }
}
