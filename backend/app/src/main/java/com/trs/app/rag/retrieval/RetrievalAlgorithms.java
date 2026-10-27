package com.trs.app.rag.retrieval;

import static com.trs.app.rag.retrieval.PythonJson.object;

import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Port of pinned keyword normalization, graph expansion, polling and reference assignment. @author
 * agent
 */
@Component
public class RetrievalAlgorithms {
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * Parses strict JSON or a fenced JSON object without splitting list-valued phrases. @author agent
   */
  public Keywords keywords(Object value) {
    try {
      JsonNode tree = value instanceof JsonNode n ? n : json.valueToTree(value);
      if (tree.isString()) {
        String text = tree.asText().replaceAll("(?s)<think>.*?</think>", "").strip();
        if (text.startsWith("```"))
          text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        tree = json.readTree(text);
      }
      if (!tree.isObject()) return new Keywords(false, List.of(), List.of());
      return new Keywords(
          true,
          keywordList(tree.path("high_level_keywords")),
          keywordList(tree.path("low_level_keywords")));
    } catch (RuntimeException invalid) {
      return new Keywords(false, List.of(), List.of());
    }
  }

  private List<String> keywordList(JsonNode value) {
    List<String> out = new ArrayList<>();
    if (value.isString()) {
      for (String part : value.asText().split("[\\n,;]+"))
        if (!part.isBlank()) out.add(part.strip());
    } else if (value.isArray())
      for (var part : value)
        if (part.isString() && !part.asText().isBlank()) out.add(part.asText().strip());
    return out;
  }

  /** Computes the upstream vector quota including its positive-input floor of one. @author agent */
  public int quota(int max, int groups) {
    return max <= 0 || groups <= 0 ? 0 : Math.max(1, max * groups / 2);
  }

  /**
   * Allocates gradient quotas using Python half-even rounding and redistributes leftovers. @author
   * agent
   */
  public List<String> weightedPolling(List<List<String>> groups, int max) {
    if (groups.isEmpty() || max <= 0) return List.of();
    if (groups.size() == 1) return groups.getFirst().stream().limit(max).toList();
    int[] used = new int[groups.size()];
    int remaining = 0;
    List<String> selected = new ArrayList<>();
    for (int i = 0; i < groups.size(); i++) {
      int expected = (int) Math.rint(max - (double) i / (groups.size() - 1) * (max - 1));
      int actual = Math.min(expected, groups.get(i).size());
      selected.addAll(groups.get(i).subList(0, actual));
      used[i] = actual;
      remaining += expected - actual;
    }
    for (int j = 0; j < remaining; j++) {
      boolean allocated = false;
      for (int i = 0; i < groups.size(); i++)
        if (used[i] < groups.get(i).size()) {
          selected.add(groups.get(i).get(used[i]++));
          allocated = true;
          break;
        }
      if (!allocated) break;
    }
    return selected;
  }

  /**
   * Interleaves ordered branches, keeping each semantic object at its first appearance. @author
   * agent
   */
  public <T> List<T> roundRobin(List<List<T>> groups, Function<T, ?> key) {
    List<T> out = new ArrayList<>();
    Set<Object> seen = new HashSet<>();
    int n = groups.stream().mapToInt(List::size).max().orElse(0);
    for (int i = 0; i < n; i++)
      for (var group : groups)
        if (i < group.size() && seen.add(key.apply(group.get(i)))) out.add(group.get(i));
    return out;
  }

  /**
   * Executes only branches selected by the requested mode, then performs upstream-order
   * merging. @author agent
   */
  public Raw search(
      SearchBackend db,
      RetrievalRequest r,
      Map<String, float[]> vectors,
      boolean low,
      boolean high) {
    List<Node> local = List.of(), global = List.of();
    List<Edge> localEdges = List.of(), globalEdges = List.of();
    List<Chunk> chunks = List.of();
    List<String> branches = new ArrayList<>();
    if (Set.of("local", "hybrid", "mix").contains(r.getMode()) && low) {
      branches.add("entities");
      local = db.entities(vectors.get("low"), r.getTopK(), r.getCosineThreshold());
      localEdges =
          db.adjacent(local).stream()
              .sorted(
                  Comparator.comparingInt(Edge::getRank)
                      .reversed()
                      .thenComparing(Comparator.comparingDouble(Edge::getWeight).reversed())
                      .thenComparing(Edge::getSource)
                      .thenComparing(Edge::getTarget))
              .toList();
    }
    if (Set.of("global", "hybrid", "mix").contains(r.getMode()) && high) {
      branches.add("relations");
      globalEdges = db.relations(vectors.get("high"), r.getTopK(), r.getCosineThreshold());
      global = db.endpoints(globalEdges);
    }
    if (Set.of("naive", "mix").contains(r.getMode())) {
      branches.add("chunks");
      chunks = db.chunks(vectors.get("query"), r.getChunkTopK(), r.getCosineThreshold());
    }
    return new Raw(
        roundRobin(List.of(local, global), Node::getName),
        roundRobin(List.of(localEdges, globalEdges), e -> List.of(e.getSource(), e.getTarget())),
        chunks,
        branches);
  }

  /**
   * Counts repeated sources, assigns ownership to the earliest graph item and sorts by
   * frequency. @author agent
   */
  public List<List<String>> sourceGroups(List<List<String>> sources, Set<String> excluded) {
    Map<String, Integer> counts = new HashMap<>();
    Set<String> seen = new HashSet<>(excluded);
    List<List<String>> groups = new ArrayList<>();
    for (var source : sources) {
      List<String> kept = new ArrayList<>();
      for (String id : source) {
        if (excluded.contains(id)) continue;
        counts.merge(id, 1, Integer::sum);
        if (seen.add(id)) kept.add(id);
      }
      if (!kept.isEmpty()) groups.add(kept);
    }
    for (var group : groups)
      group.sort(Comparator.comparingInt((String id) -> counts.get(id)).reversed());
    return groups;
  }

  /**
   * Computes reference IDs after final truncation, retaining every captured source
   * occurrence. @author agent
   */
  public ReferenceResult references(List<Chunk> chunks) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    Map<String, Origin> originByPath = new LinkedHashMap<>();
    for (var chunk : chunks) {
      Set<String> paths = new LinkedHashSet<>();
      if (!chunk.getOrigins().isEmpty())
        for (var origin : chunk.getOrigins()) {
          paths.add(origin.getSourceName());
          originByPath.putIfAbsent(origin.getSourceName(), origin);
        }
      else if (!chunk.getFilePath().isBlank() && !chunk.getFilePath().equals("unknown_source"))
        paths.add(chunk.getFilePath());
      for (String path : paths) counts.merge(path, 1, Integer::sum);
    }
    List<String> sorted = new ArrayList<>(counts.keySet());
    sorted.sort(Comparator.comparingInt((String path) -> counts.get(path)).reversed());
    Map<String, String> ids = new LinkedHashMap<>();
    List<Map<String, Object>> references = new ArrayList<>();
    for (String path : sorted) {
      String id = Integer.toString(ids.size() + 1);
      ids.put(path, id);
      var ref = object("reference_id", id, "file_path", path);
      var origin = originByPath.get(path);
      if (origin != null) {
        ref.put("documentId", origin.getDocumentId());
        ref.put("revisionId", origin.getRevisionId());
      }
      references.add(ref);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var chunk : chunks) {
      var row =
          object(
              "chunk_id",
              chunk.getId(),
              "content",
              chunk.getContent(),
              "file_path",
              chunk.getFilePath(),
              "reference_id",
              ids.getOrDefault(chunk.getFilePath(), ""));
      if (!chunk.getHeadings().isEmpty()) row.put("content_headings", chunk.getHeadings());
      if (!chunk.getOrigins().isEmpty()) {
        row.put("sources", chunk.getOrigins());
        row.put(
            "reference_ids",
            chunk.getOrigins().stream().map(o -> ids.get(o.getSourceName())).distinct().toList());
      } else row.put("sources", List.of());
      if (chunk.getScore() != null) row.put("score", chunk.getScore());
      if (chunk.getRerankScore() != null) row.put("rerank_score", chunk.getRerankScore());
      rows.add(row);
    }
    return new ReferenceResult(references, rows);
  }

  /** Renders exactly the upstream reference/content/optional-heading projection. @author agent */
  public String renderChunks(List<Map<String, Object>> chunks) {
    List<String> lines = new ArrayList<>();
    for (var row : chunks) {
      var projected =
          object("reference_id", row.get("reference_id"), "content", row.get("content"));
      if (row.containsKey("content_headings"))
        projected.put("content_headings", row.get("content_headings"));
      lines.add(PythonJson.render(projected));
    }
    return String.join("\n", lines);
  }
}
