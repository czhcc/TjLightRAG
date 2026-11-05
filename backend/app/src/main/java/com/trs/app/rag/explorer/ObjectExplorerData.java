package com.trs.app.rag.explorer;

import com.trs.app.rag.graph.GraphData.Node;
import java.util.*;
import lombok.Value;

/** Read-only object browsing projections within a published generation. @author czhcc */
public final class ObjectExplorerData {
  private ObjectExplorerData() {}

  /** Stable bounded result page with its exact filtered count. @author czhcc */
  @Value
  public static class Page<T> {
    List<T> items;
    long total;
    int page, size;
  }

  /** Count of merged objects in one exact entity type. @author czhcc */
  @Value
  public static class TypeCount {
    String type;
    long count;
  }

  /** Type totals and a name/type-filtered page from the same generation. @author czhcc */
  @Value
  public static class Listing {
    UUID generationId;
    long allCount;
    List<TypeCount> types;
    Page<Node> objects;
  }

  /** One source occurrence in a document revision captured by the index. @author czhcc */
  @Value
  public static class Text {
    UUID chunkId, documentId, revisionId;
    String sourceName;
    int chunkOrder;
    String content;
  }

  /** Neighbor object and the relation joining it to the selected object. @author czhcc */
  @Value
  public static class Related {
    Node entity;
    List<String> types;
    String description;
  }

  /** Full object properties with independently paged evidence and neighbors. @author czhcc */
  @Value
  public static class Detail {
    UUID generationId;
    Node entity;
    Page<Text> texts;
    Page<Related> related;
  }
}
