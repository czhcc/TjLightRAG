package com.trs.app.rag.index;

import java.util.*;
import lombok.*;

/** Versioned per-chunk contribution; no accumulated graph weights. @author agent */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Extraction {
  private List<Entity> entities = new ArrayList<>();
  private List<Relation> relations = new ArrayList<>();

  /** One normalized entity contribution. @author agent */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Entity {
    private String name, type, description;
  }

  /** One canonical undirected relation contribution. @author agent */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Relation {
    private String source, target, keywords, description;
    private double weight = 1;
  }
}
