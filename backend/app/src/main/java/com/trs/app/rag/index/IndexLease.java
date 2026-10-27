package com.trs.app.rag.index;

import java.util.UUID;
import lombok.Value;

/** Captured immutable job and model identities plus a transient fencing token. @author agent */
@Value
public class IndexLease {
  UUID knowledgeBaseId, id, token;
  String actor;
  UUID extractVersion, summaryVersion, embeddingVersion;
  int dimensions, gleaning;
  String promptVersion, tokenizer;
}
