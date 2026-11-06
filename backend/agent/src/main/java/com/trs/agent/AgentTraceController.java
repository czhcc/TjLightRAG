package com.trs.agent;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Private per-answer trace summaries and lazy encrypted payload reads. @author czhcc */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-bases/{kb}/conversations/{conversation}/turns/{turn}/trace")
public class AgentTraceController {
    private final AgentTraceRecorder traces;
    /** Reads sequence-paged summaries without exposing model bodies. @author czhcc */
    @GetMapping
    public Map<String, Object> view(
            @PathVariable UUID kb,
            @PathVariable UUID conversation,
            @PathVariable UUID turn,
            @RequestParam(defaultValue = "0") int afterSequence,
            @RequestParam(defaultValue = "100") int size,
            Principal actor,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return traces.view(kb, conversation, turn, actor.getName(), afterSequence, size);
    }
    /** Decrypts one authorized request or response segment. @author czhcc */
    @GetMapping("/spans/{span}")
    public Map<String, Object> payload(
            @PathVariable UUID kb,
            @PathVariable UUID conversation,
            @PathVariable UUID turn,
            @PathVariable UUID span,
            @RequestParam(defaultValue = "request") String side,
            @RequestParam(defaultValue = "0") int part,
            Principal actor,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return traces.payload(kb, conversation, turn, span, actor.getName(), side, part);
    }
}
