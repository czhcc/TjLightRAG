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
    private List<Attribute> attributes = new ArrayList<>();

    /** Retains compatibility with historical extraction checkpoints. @author czhcc */
    public Entity(String name, String type, String description) {
      this(name, type, description, new ArrayList<>());
    }

    /** Converts historical string attributes to explicit typed values. @author czhcc */
    public Entity(String name, String type, String description, Map<String, String> attributes) {
      this(name, type, description, attributes.entrySet().stream()
          .map(e -> new Attribute(e.getKey(), "string", tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(e.getValue()))).toList());
    }
  }

  /** One named, typed attribute value grounded in the entity's source chunks. @author czhcc */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Attribute {
    private String name, type;
    private tools.jackson.databind.JsonNode value;
  }

  /** One canonical undirected relation contribution. @author agent */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Relation {
    private String source, target, keywords, description;
    private double weight = 1;
    private String type = "";
    private String sourceType = "", targetType = "";

    /** Supports unambiguous legacy name-based relationships. @author czhcc */
    public Relation(String source, String target, String keywords, String description, double weight, String type) {
      this(source, target, keywords, description, weight, type, "", "");
    }

    /** Retains compatibility with historical extraction checkpoints. @author czhcc */
    public Relation(String source, String target, String keywords, String description, double weight) {
      this(source, target, keywords, description, weight, "");
    }
  }
}
