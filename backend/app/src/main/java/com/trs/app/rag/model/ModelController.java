package com.trs.app.rag.model;

import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Session/CSRF-protected administrator model APIs. @author agent */
@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class ModelController {
  private final ModelConfigurationService service;

  /** Lists masked model configurations. @author agent */
  @GetMapping
  public List<Map<String, Object>> list(Principal user) {
    return service.list(user.getName());
  }

  /** Reads nonsecret deployment readiness. @author agent */
  @GetMapping("/status")
  public Map<String, Object> status(Principal user) {
    return service.status(user.getName());
  }

  /** Creates the first immutable configuration version. @author agent */
  @PostMapping
  public Map<String, Object> create(@RequestBody ModelRequest body, Principal user) {
    return service.save(null, body, user.getName());
  }

  /** Appends a version without modifying historical credentials or indexes. @author agent */
  @PutMapping("/{profile}")
  public Map<String, Object> update(
      @PathVariable UUID profile, @RequestBody ModelRequest body, Principal user) {
    return service.save(profile, body, user.getName());
  }

  /** Lists masked configuration history. @author agent */
  @GetMapping("/{profile}/versions")
  public List<Map<String, Object>> history(@PathVariable UUID profile, Principal user) {
    return service.history(profile, user.getName());
  }

  /** Probes the captured protocol configuration. @author agent */
  @PostMapping("/versions/{version}/test")
  public Map<String, Object> test(@PathVariable UUID version, Principal user) {
    return service.test(version, user.getName());
  }

  /** Reads purpose bindings. @author agent */
  @GetMapping("/bindings")
  public Map<String, UUID> bindings(Principal user) {
    return service.bindings(user.getName());
  }

  /** Saves purpose bindings; null values unbind optional purposes. @author agent */
  @PutMapping("/bindings")
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.NO_CONTENT)
  public void bind(@RequestBody Map<String, UUID> body, Principal user) {
    service.bind(body, user.getName());
  }
}
