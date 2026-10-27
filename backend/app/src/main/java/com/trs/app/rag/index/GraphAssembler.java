package com.trs.app.rag.index;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Full-generation deterministic graph merge from immutable chunk contributions. @author agent */
@Component
@RequiredArgsConstructor
public class GraphAssembler {
  private final ModelGateway gateway;
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();

  /** Final graph publication batch; vectors remain separated by collection. @author agent */
  @Value
  public static class Graph {
    List<Node> entities;
    List<Edge> relations;
  }

  /** Merged node including unabridged source IDs. @author agent */
  @Getter
  @Setter
  public static class Node {
    private UUID id;
    private String name, type, description;
    private List<UUID> sources;
    private float[] vector;
  }

  /** Merged undirected edge with one weight contribution per shared chunk. @author agent */
  @Getter
  @Setter
  public static class Edge {
    private UUID id, source, target;
    private String description, keywords;
    private double weight;
    private List<UUID> sources;
    private float[] vector;
  }

  private static class NodeParts {
    final Map<String, Integer> types = new TreeMap<>();
    final LinkedHashSet<String> descriptions = new LinkedHashSet<>();
    final Set<UUID> sources = new LinkedHashSet<>();
  }

  private static class EdgeParts {
    final LinkedHashSet<String> descriptions = new LinkedHashSet<>(),
        keywords = new LinkedHashSet<>();
    final Set<UUID> sources = new LinkedHashSet<>();
  }

  /**
   * Rebuilds merges, summaries and vectors without accumulating previous attempt weights. @author
   * agent
   */
  public Graph assemble(
      Map<UUID, Extraction> contributions,
      ModelVersion summary,
      ModelVersion embedding,
      Runnable check) {
    Map<String, NodeParts> nodes = new TreeMap<>();
    Map<List<String>, EdgeParts> edges = new LinkedHashMap<>();
    for (var chunk : contributions.entrySet()) {
      check.run();
      for (var e : chunk.getValue().getEntities()) {
        var n = nodes.computeIfAbsent(e.getName(), k -> new NodeParts());
        n.types.merge(e.getType(), 1, Integer::sum);
        n.descriptions.add(e.getDescription());
        n.sources.add(chunk.getKey());
      }
      for (var r : chunk.getValue().getRelations()) {
        var edge =
            edges.computeIfAbsent(List.of(r.getSource(), r.getTarget()), k -> new EdgeParts());
        edge.descriptions.add(r.getDescription());
        for (String k : r.getKeywords().split(",")) if (!k.isBlank()) edge.keywords.add(k.strip());
        edge.sources.add(chunk.getKey());
        for (String name : List.of(r.getSource(), r.getTarget())) {
          var n = nodes.computeIfAbsent(name, k -> new NodeParts());
          n.sources.add(chunk.getKey());
        }
      }
      if (nodes.size() > 10000 || edges.size() > 30000) throw new ModelFailure("graph_size_limit");
    }
    List<Node> resultNodes = new ArrayList<>();
    Map<String, UUID> ids = new HashMap<>();
    for (var e : nodes.entrySet()) {
      check.run();
      Node n = new Node();
      n.setId(UUID.nameUUIDFromBytes(e.getKey().getBytes(StandardCharsets.UTF_8)));
      ids.put(e.getKey(), n.getId());
      n.setName(e.getKey());
      n.setType(
          e.getValue().types.entrySet().stream()
              .sorted(
                  Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                      .reversed()
                      .thenComparing(Map.Entry::getKey))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse("unknown"));
      var desc = e.getValue().descriptions;
      if (desc.isEmpty()) {
        for (var edge : edges.entrySet())
          if (edge.getKey().contains(e.getKey())) desc.addAll(edge.getValue().descriptions);
      }
      n.setDescription(describe("entity", e.getKey(), new ArrayList<>(desc), summary, check));
      n.setSources(new ArrayList<>(e.getValue().sources));
      n.setVector(gateway.embed(embedding, n.getName() + "\n" + n.getDescription()));
      check.run();
      resultNodes.add(n);
    }
    List<Edge> resultEdges = new ArrayList<>();
    for (var e : edges.entrySet()) {
      check.run();
      var r = new Edge();
      String src = e.getKey().get(0), tgt = e.getKey().get(1);
      UUID a = ids.get(src), b = ids.get(tgt);
      r.setId(UUID.nameUUIDFromBytes((src + "\u0000" + tgt).getBytes(StandardCharsets.UTF_8)));
      if (a.toString().compareTo(b.toString()) > 0) {
        UUID temp = a;
        a = b;
        b = temp;
      }
      r.setSource(a);
      r.setTarget(b);
      r.setDescription(
          describe(
              "relationship",
              src + "~" + tgt,
              new ArrayList<>(e.getValue().descriptions),
              summary,
              check));
      r.setKeywords(String.join(",", e.getValue().keywords.stream().sorted().toList()));
      r.setSources(new ArrayList<>(e.getValue().sources));
      r.setWeight(r.getSources().size());
      r.setVector(
          gateway.embed(
              embedding, r.getKeywords() + "\t" + src + "\n" + tgt + "\n" + r.getDescription()));
      check.run();
      resultEdges.add(r);
    }
    return new Graph(resultNodes, resultEdges);
  }

  /**
   * Deduplicates descriptions and performs bounded hierarchical summaries without dropping facts
   * locally. @author agent
   */
  public String describe(
      String kind, String name, List<String> descriptions, ModelVersion model, Runnable check) {
    List<String> current = descriptions.stream().filter(s -> !s.isBlank()).distinct().toList();
    if (current.isEmpty()) throw new ModelFailure("graph_description_missing");
    String joined = String.join("<SEP>", current);
    if (current.size() < 10 && tokenizer.encode(joined).length <= 1000) return joined;
    String template =
        PromptTemplates.format(
            PromptTemplates.load("summary.txt"),
            Map.of(
                "language",
                "English",
                "summary_length",
                "1000",
                "description_type",
                kind,
                "description_name",
                name));
    int available =
        model.getContextTokens()
            - model.getMaxOutputTokens()
            - tokenizer.encode(template).length
            - 128;
    if (available < 128) throw new ModelFailure("model_context_exceeded");
    for (int pass = 0; pass < 8; pass++) {
      check.run();
      List<String> pieces = new ArrayList<>();
      for (String s : current) pieces.addAll(split(s, Math.min(available / 2, 4096)));
      List<String> next = new ArrayList<>(), batch = new ArrayList<>();
      int used = 0;
      for (String s : pieces) {
        String line = json.writeValueAsString(Map.of("description", s));
        int size = tokenizer.encode(line).length + 2;
        if (!batch.isEmpty() && used + size > available) {
          next.add(summarize(template, batch, model, check));
          batch.clear();
          used = 0;
        }
        batch.add(line);
        used += size;
      }
      if (!batch.isEmpty()) next.add(summarize(template, batch, model, check));
      if (next.size() == 1 && tokenizer.encode(next.getFirst()).length <= 1000)
        return next.getFirst();
      if (String.join("", next).equals(String.join("", current)))
        throw new ModelFailure("summary_not_converging");
      current = next;
    }
    throw new ModelFailure("summary_not_converging");
  }

  private String summarize(
      String template, List<String> lines, ModelVersion model, Runnable check) {
    check.run();
    String result =
        gateway.chat(
            model,
            List.of(
                Map.of(
                    "role",
                    "user",
                    "content",
                    template.replace("{description_list}", String.join("\n", lines)))));
    check.run();
    return result.strip();
  }

  private List<String> split(String source, int budget) {
    List<String> parts = new ArrayList<>();
    String remaining = source;
    while (tokenizer.encode(remaining).length > budget) {
      int count = remaining.codePointCount(0, remaining.length()), low = 1, high = count;
      while (low < high) {
        int mid = (low + high + 1) / 2;
        if (tokenizer.encode(remaining.substring(0, remaining.offsetByCodePoints(0, mid))).length
            <= budget) low = mid;
        else high = mid - 1;
      }
      int end = remaining.offsetByCodePoints(0, low);
      parts.add(remaining.substring(0, end));
      remaining = remaining.substring(end);
    }
    if (!remaining.isEmpty()) parts.add(remaining);
    return parts;
  }
}
