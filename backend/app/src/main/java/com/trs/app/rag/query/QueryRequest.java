package com.trs.app.rag.query;

import com.trs.app.rag.retrieval.RetrievalRequest;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.*;

/**
 * Query with an optional private conversation; history is always read by the server. @author czhcc
 */
@Getter
@Setter
@NoArgsConstructor
public class QueryRequest extends RetrievalRequest {
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean explicitTotalBudget;

    /** Records a caller budget separately from the inherited retrieval default. @author czhcc */
    @Override
    public void setMaxTotalTokens(int tokens) {
        super.setMaxTotalTokens(tokens);
        explicitTotalBudget = true;
    }

    /** Distinguishes omitted budgets from explicit API limits. @author czhcc */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasExplicitTotalBudget() {
        return explicitTotalBudget;
    }

    /** Applies an internal planning limit without changing the caller's budget policy. @author czhcc */
    public void usePlanningBudget(int tokens) {
        super.setMaxTotalTokens(tokens);
    }

    private UUID requestId;
    private UUID conversationId;

    @NotNull
    @Pattern(regexp = "answer|context|prompt")
    private String preview = "answer";

    private boolean enableRerank = false;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minRerankScore = .5;

    /**
     * Rejects unsupported history/cache/system overrides instead of silently ignoring them. @author
     * agent
     */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void unsupported(String name, Object value) {
        throw new IllegalArgumentException("Unsupported query parameter");
    }
}
