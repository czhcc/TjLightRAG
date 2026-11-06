package com.trs.app.rag.model;

import java.util.*;
import lombok.*;

/**
 * Immutable internal model snapshot; encrypted credentials are excluded from string
 * rendering. @author agent
 */
@Value
public class ModelVersion {
    UUID id, profileId;
    String kind, baseUrl, model;

    @ToString.Exclude
    String cipher;

    int dimensions, contextTokens, maxOutputTokens, timeoutSeconds;

    @ToString.Exclude
    Map<String, Object> extraBody;

    /** Creates an immutable snapshot including provider-specific JSON extensions. @author czhcc */
    public ModelVersion(
            UUID id,
            UUID profileId,
            String kind,
            String baseUrl,
            String model,
            String cipher,
            int dimensions,
            int contextTokens,
            int maxOutputTokens,
            int timeoutSeconds,
            Map<String, Object> extraBody) {
        this.id = id;
        this.profileId = profileId;
        this.kind = kind;
        this.baseUrl = baseUrl;
        this.model = model;
        this.cipher = cipher;
        this.dimensions = dimensions;
        this.contextTokens = contextTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.extraBody = ModelExtraParameters.copy(extraBody);
        if (!"CHAT".equals(kind) && !this.extraBody.isEmpty())
            throw new IllegalArgumentException("Extensions require a text model");
    }

    /** Historical callers and versions use an empty extension object. @author czhcc */
    public ModelVersion(
            UUID id,
            UUID profileId,
            String kind,
            String baseUrl,
            String model,
            String cipher,
            int dimensions,
            int contextTokens,
            int maxOutputTokens,
            int timeoutSeconds) {
        this(
                id,
                profileId,
                kind,
                baseUrl,
                model,
                cipher,
                dimensions,
                contextTokens,
                maxOutputTokens,
                timeoutSeconds,
                Map.of());
    }
}
