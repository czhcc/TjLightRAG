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

    /** Creates a current model configuration. @author czhcc */
    @PostMapping
    public Map<String, Object> create(@RequestBody ModelRequest body, Principal user) {
        return service.save(null, body, user.getName());
    }

    /** Updates the configuration while preserving captured index inputs. @author czhcc */
    @PutMapping("/{profile}")
    public Map<String, Object> update(@PathVariable UUID profile, @RequestBody ModelRequest body, Principal user) {
        return service.save(profile, body, user.getName());
    }

    /** Probes the current model configuration. @author czhcc */
    @PostMapping("/{model}/test")
    public Map<String, Object> test(@PathVariable UUID model, Principal user) {
        return service.test(model, user.getName());
    }

    /** Deletes a model after all knowledge-base bindings have been removed. @author czhcc */
    @DeleteMapping("/{model}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID model, Principal user) {
        service.delete(model, user.getName());
    }
}
