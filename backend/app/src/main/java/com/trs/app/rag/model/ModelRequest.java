package com.trs.app.rag.model;

import java.util.Map;
import lombok.*;

/** Administrator-only model configuration input; never log this object. @author agent */
@Getter
@Setter
@NoArgsConstructor
public class ModelRequest {
    private boolean clearApiKey;
    private String name, kind, baseUrl, model, apiKey;
    private int dimensions = 0, contextTokens = 16000, maxOutputTokens = 2000, timeoutSeconds = 30;
    private Map<String, Object> extraBody = Map.of();
}
