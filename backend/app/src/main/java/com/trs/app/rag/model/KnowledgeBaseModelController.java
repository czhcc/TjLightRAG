package com.trs.app.rag.model;

import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Membership-scoped model choices and owner-controlled uses. @author czhcc */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}")
@RequiredArgsConstructor
public class KnowledgeBaseModelController {
    private final ModelConfigurationService service;

    /** Returns the selected knowledge base's purpose settings. @author czhcc */
    @GetMapping("/model-bindings")
    public Map<String, UUID> bindings(@PathVariable UUID kb, Principal user) {
        return service.bindings(kb, user.getName());
    }

    /** Returns safe model names and types to knowledge-base members. @author czhcc */
    @GetMapping("/model-options")
    public List<Map<String, Object>> options(@PathVariable UUID kb, Principal user) {
        return service.options(kb, user.getName());
    }

    /** Saves this knowledge base's bindings after owner authorization. @author czhcc */
    @PutMapping("/model-bindings")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void bind(@PathVariable UUID kb, @RequestBody Map<String, UUID> body, Principal user) {
        service.bind(kb, body, user.getName());
    }
}
