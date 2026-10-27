package com.trs.app.rag.retrieval;

import java.util.*;
import lombok.Value;

/**
 * Immutable query-local retrieval data; never contains credentials or shared mutable state. @author
 * agent
 */
public final class RetrievalData {
  private RetrievalData() {}

  /** Index and model versions captured once at query start. @author agent */
  @Value
  public static class Snapshot {
    UUID knowledgeBaseId, generationId, embeddingVersion, keywordVersion;
    int dimensions;
    String tokenizer;
  }

  /** Graph entity in recall order, with its complete contribution IDs. @author agent */
  @Value
  public static class Node {
    String id, name, type, description;
    List<String> sourceIds;
    int degree;
    Double score;
  }

  /** Canonical name-ordered undirected edge. @author agent */
  @Value
  public static class Edge {
    String id, source, target, description, keywords;
    double weight;
    List<String> sourceIds;
    int rank;
    Double score;
  }

  /** One immutable revision-specific occurrence of a shared chunk. @author agent */
  @Value
  public static class Origin {
    String documentId, revisionId, sourceName;
    int chunkOrder, sourceStart, sourceEnd;
    List<String> headings;
  }

  /** Shared evidence text with all bounded, explicitly scoped source occurrences. @author agent */
  @Value
  @lombok.AllArgsConstructor
  public static class Chunk {
    String id, content, filePath;
    List<String> headings;
    Double score;
    List<Origin> origins;
    Double rerankScore;

    /**
     * Creates an unreranked chunk without confusing cosine similarity and relevance. @author agent
     */
    public Chunk(
        String id,
        String content,
        String filePath,
        List<String> headings,
        Double score,
        List<Origin> origins) {
      this(id, content, filePath, headings, score, origins, null);
    }
  }

  /** Validity is separate from a well-formed but empty keyword result. @author agent */
  @Value
  public static class Keywords {
    boolean valid;
    List<String> high, low;
  }

  /** Raw graph and vector candidates before budget pruning. @author agent */
  @Value
  public static class Raw {
    List<Node> nodes;
    List<Edge> edges;
    List<Chunk> vectors;
    List<String> branches;
  }

  /** Graph candidates after serialized-prefix and full-prompt budget checks. @author agent */
  @Value
  public static class GraphSlice {
    List<Node> nodes;
    List<Edge> edges;
  }

  /** Recomputed document references and corresponding chunk projections. @author agent */
  @Value
  public static class ReferenceResult {
    List<Map<String, Object>> references, chunks;
  }

  /** Final deterministic context and exact token accounting. @author agent */
  @Value
  public static class ContextPlan {
    String context;
    List<Map<String, Object>> chunks, references;
    Map<String, Integer> budget;
  }

  /**
   * Observable retrieval data; no language-model answer is generated in stage four. @author agent
   */
  @Value
  public static class Result {
    String status, mode;
    UUID generationId, embeddingVersion, keywordVersion;
    Keywords keywords;
    List<Node> entities;
    List<Edge> relations;
    List<Map<String, Object>> chunks, references;
    String context;
    Map<String, Object> metadata;
    boolean reranked;
  }
}
