package com.trs.app.rag.query;

import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Session-authenticated, knowledge-base and user-scoped conversation APIs. @author czhcc */
@RestController
@RequestMapping("/api/knowledge-bases/{kb}/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService service;

    /** Lists the current user's recent conversations only. @author czhcc */
    @GetMapping
    public List<Map<String, Object>> list(
            @PathVariable UUID kb, Principal actor, @RequestParam(defaultValue = "0") int page) {
        return service.list(kb, actor.getName(), page);
    }

    /** Creates a private conversation in the selected knowledge base. @author czhcc */
    @PostMapping
    public Map<String, Object> create(@PathVariable UUID kb, Principal actor) {
        return service.create(kb, actor.getName());
    }

    /** Deletes the current user's conversation and all its question and answer records. @author czhcc */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID kb, @PathVariable UUID id, Principal actor) {
        service.delete(kb, id, actor.getName());
    }

    /** Reads a bounded page of questions, answers and their original evidence. @author czhcc */
    @GetMapping("/{id}/turns")
    public List<Map<String, Object>> turns(
            @PathVariable UUID kb,
            @PathVariable UUID id,
            Principal actor,
            @RequestParam(required = false) Long before) {
        return service.turns(kb, id, actor.getName(), before);
    }
}
