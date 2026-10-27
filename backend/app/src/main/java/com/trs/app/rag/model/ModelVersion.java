package com.trs.app.rag.model;

import java.util.UUID;
import lombok.*;

/**
 * Immutable internal model snapshot; encrypted credentials are excluded from string
 * rendering. @author agent
 */
@Value
public class ModelVersion {
  UUID id, profileId;
  String kind, baseUrl, model;
  @ToString.Exclude String cipher;
  int dimensions, contextTokens, maxOutputTokens, timeoutSeconds;
}
