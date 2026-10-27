package com.trs.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.ingestion.Cl100kTokenizer;
import com.trs.app.rag.retrieval.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production candidate comparisons against the pinned, unmodified upstream corpus. @author agent
 */
class RetrievalAlgorithmsTest {
  final JsonMapper json = JsonMapper.builder().build();
  final RetrievalAlgorithms algorithms = new RetrievalAlgorithms();

  JsonNode cases() throws Exception {
    try (var in = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
      return json.readTree(in).path("cases");
    }
  }

  List<String> strings(JsonNode a) {
    List<String> values = new ArrayList<>();
    a.forEach(v -> values.add(v.asText()));
    return values;
  }

  @Test
  void keywordsAndQuotaMatchNineFrozenCases() throws Exception {
    int count = 0;
    for (var c : cases()) {
      if (c.path("family").asText().equals("keywords")) {
        var actual = algorithms.keywords(c.path("input").path("model_response"));
        assertEquals(c.path("expected").get(0).asBoolean(), actual.isValid());
        assertEquals(strings(c.path("expected").get(1)), actual.getHigh());
        assertEquals(strings(c.path("expected").get(2)), actual.getLow());
        count++;
      }
      if (c.path("family").asText().equals("quota")) {
        assertEquals(
            c.path("expected").asInt(),
            algorithms.quota(
                c.path("input").path("max").asInt(), c.path("input").path("groups").asInt()));
        count++;
      }
    }
    assertEquals(9, count);
  }

  @Test
  void graphModesMatchFrozenCandidatesOrderDegreesAndBranchCalls() throws Exception {
    int count = 0;
    for (var c : cases()) {
      if (!Set.of("search-local", "search-global", "search-hybrid", "search-mix")
          .contains(c.path("id").asText())) continue;
      var in = c.path("input");
      var backend = new MemoryBackend(in);
      var req = new RetrievalRequest();
      req.setMode(in.path("mode").asText());
      req.setTopK(2);
      req.setChunkTopK(2);
      var vectors =
          Map.of(
              "query",
              new float[] {1, 0, 0},
              "low",
              new float[] {1, 0, 0},
              "high",
              new float[] {0, 1, 0});
      var result = algorithms.search(backend, req, vectors, true, true);
      var expected = c.path("expected").path("result");
      assertEquals(
          stringsField(expected.path("final_entities"), "entity_name"),
          result.getNodes().stream().map(Node::getName).toList(),
          req.getMode());
      List<List<String>> expectedEdges = new ArrayList<>();
      for (var e : expected.path("final_relations"))
        expectedEdges.add(
            e.has("src_tgt")
                ? strings(e.path("src_tgt"))
                : List.of(e.path("src_id").asText(), e.path("tgt_id").asText()));
      assertEquals(
          expectedEdges,
          result.getEdges().stream().map(e -> List.of(e.getSource(), e.getTarget())).toList(),
          req.getMode());
      assertEquals(
          stringsField(expected.path("vector_chunks"), "chunk_id"),
          result.getVectors().stream().map(Chunk::getId).toList());
      assertEquals(c.path("expected").path("calls").path("entities").size(), backend.entityCalls);
      assertEquals(
          c.path("expected").path("calls").path("relations").size(), backend.relationCalls);
      assertEquals(c.path("expected").path("calls").path("chunks").size(), backend.chunkCalls);
      count++;
    }
    assertEquals(4, count);
  }

  List<String> stringsField(JsonNode rows, String field) {
    List<String> result = new ArrayList<>();
    rows.forEach(r -> result.add(r.path(field).asText()));
    return result;
  }

  @Test
  void referencesAndExactUnicodeContextMatchFrozenRenderer() throws Exception {
    int count = 0;
    for (var c : cases()) {
      if (!c.path("family").asText().equals("references")
          && !c.path("family").asText().equals("context")) continue;
      var chunks = chunks(c.path("input").path("chunks"));
      var result = algorithms.references(chunks);
      if (c.path("family").asText().equals("references")) {
        assertEquals(
            c.path("expected").path("references"), json.valueToTree(result.getReferences()));
        for (int i = 0; i < chunks.size(); i++)
          assertEquals(
              c.path("expected").path("chunks").get(i).path("reference_id").asText(),
              result.getChunks().get(i).get("reference_id"));
      } else {
        String rendered = algorithms.renderChunks(result.getChunks());
        assertEquals(c.path("expected").path("context").asText(), rendered);
        assertEquals(
            c.path("expected").path("prompt").asText(),
            new ContextPlanner(new Cl100kTokenizer(), algorithms).systemPrompt(rendered));
      }
      count++;
    }
    assertEquals(3, count);
  }

  List<Chunk> chunks(JsonNode rows) {
    List<Chunk> result = new ArrayList<>();
    for (var r : rows)
      result.add(
          new Chunk(
              r.path("chunk_id").asText(),
              r.path("content").asText(),
              r.path("file_path").asText(),
              List.of(),
              null,
              List.of()));
    return result;
  }

  @Test
  void weightedPollingUsesBankersRoundingAndRedistributesUnusedQuota() {
    var groups = List.of(List.of("a"), List.of("b", "c", "d", "e"), List.of("f", "g", "h", "i"));
    assertEquals(List.of("a", "b", "c", "f", "d", "e", "g"), algorithms.weightedPolling(groups, 4));
    assertEquals(List.of(), algorithms.weightedPolling(groups, 0));
    assertEquals(List.of("x", "y"), algorithms.weightedPolling(List.of(List.of("x", "y", "z")), 2));
  }

  @Test
  void missingKeywordsNeverRunAnOppositeModeOrDropMixVectorBranch() {
    var backend = new MemoryBackend(null);
    var req = new RetrievalRequest();
    req.setMode("local");
    algorithms.search(backend, req, Map.of(), false, true);
    assertEquals(0, backend.relationCalls);
    req.setMode("global");
    algorithms.search(backend, req, Map.of(), true, false);
    assertEquals(0, backend.entityCalls);
    req.setMode("mix");
    algorithms.search(backend, req, Map.of("query", new float[] {1, 0, 0}), false, false);
    assertEquals(1, backend.chunkCalls);
  }

  @Test
  void actualBpeBudgetRechecksReferencesAndFullRenderedPrompt() {
    var tokenizer = new Cl100kTokenizer();
    var planner = new ContextPlanner(tokenizer, algorithms);
    var req = new RetrievalRequest();
    req.setMode("mix");
    req.setMaxTotalTokens(2400);
    req.setMaxEntityTokens(0);
    req.setMaxRelationTokens(0);
    List<Chunk> chunks = new ArrayList<>();
    for (int i = 0; i < 30; i++)
      chunks.add(
          new Chunk(
              "c" + i,
              "知识图谱🙂🚀 exact whole chunk ".repeat(10),
              i % 2 == 0 ? "中文.md" : "english.md",
              List.of("章节🙂"),
              null,
              List.of()));
    var graph =
        planner.graph("知识图谱怎么检索？", req, new Raw(List.of(), List.of(), chunks, List.of("chunks")));
    var result = planner.finish("知识图谱怎么检索？", req, graph, chunks);
    assertTrue(result.getChunks().size() > 0 && result.getChunks().size() < 30);
    assertTrue(result.getBudget().get("totalTokens") <= 2400);
    assertEquals(
        tokenizer.encode(planner.systemPrompt(result.getContext())).length,
        result.getBudget().get("promptTokens"));
    assertFalse(result.getContext().contains("\ufffd"));
    for (int i = 0; i < result.getChunks().size(); i++)
      assertEquals(chunks.get(i).getContent(), result.getChunks().get(i).get("content"));
  }

  @Test
  void naiveRecallAndThreeWaySourceMergeMatchFrozenCases() throws Exception {
    int count = 0;
    for (var c : cases()) {
      if (c.path("id").asText().equals("search-naive")) {
        var req = new RetrievalRequest();
        req.setMode("naive");
        req.setChunkTopK(2);
        var backend = new MemoryBackend(c.path("input"));
        var result =
            algorithms.search(backend, req, Map.of("query", new float[] {1, 0, 0}), false, false);
        assertEquals(
            stringsField(c.path("expected").path("result"), "chunk_id"),
            result.getVectors().stream().map(Chunk::getId).toList());
        assertEquals(0, backend.entityCalls);
        assertEquals(0, backend.relationCalls);
        assertEquals(1, backend.chunkCalls);
        count++;
      }
      if (c.path("family").asText().equals("merge_chunks")) {
        var input = c.path("input");
        List<List<String>> entities = new ArrayList<>(), relations = new ArrayList<>();
        for (var e : input.path("entities"))
          entities.add(Arrays.asList(e.path("source_id").asText().split("<SEP>")));
        for (var e : input.path("relations"))
          relations.add(Arrays.asList(e.path("source_id").asText().split("<SEP>")));
        var selected = algorithms.weightedPolling(algorithms.sourceGroups(entities, Set.of()), 2);
        var relation =
            algorithms.weightedPolling(
                algorithms.sourceGroups(relations, new HashSet<>(selected)), 2);
        var merged =
            algorithms.roundRobin(
                List.of(stringsField(input.path("vector_chunks"), "chunk_id"), selected, relation),
                v -> v);
        assertEquals(stringsField(c.path("expected"), "chunk_id"), merged);
        count++;
      }
    }
    assertEquals(2, count);
  }

  @Test
  void scalarBudgetFixturesCompareTheProductionPrefixSelector() throws Exception {
    var scalar =
        new com.trs.app.rag.ingestion.RagTokenizer() {
          /** Implements the independent reference fixture contract. @author agent */
          public String getName() {
            return "unicode-codepoint-test-v1";
          }

          /** Implements the independent reference fixture contract. @author agent */
          public int[] encode(String text) {
            return text.codePoints().toArray();
          }

          /** Implements the independent reference fixture contract. @author agent */
          public byte[] tokenBytes(int token) {
            return new String(Character.toChars(token))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          }
        };
    var planner = new ContextPlanner(scalar, algorithms);
    int count = 0;
    for (var c : cases())
      if (c.path("family").asText().equals("budget")) {
        var actual =
            planner.prefix(
                strings(c.path("input").path("items")),
                v -> String.join(c.path("input").path("separator").asText(), v),
                c.path("input").path("limit").asInt());
        assertEquals(strings(c.path("expected")), actual);
        count++;
      }
    assertEquals(4, count);
  }

  /** Independent dense-vector and graph fixture backend. @author agent */
  static class MemoryBackend implements SearchBackend {
    final JsonNode in;
    int entityCalls, relationCalls, chunkCalls;

    MemoryBackend(JsonNode in) {
      this.in = in;
    }

    /** Implements the independent reference fixture contract. @author agent */
    public List<Node> entities(float[] v, int k, double threshold) {
      entityCalls++;
      if (in == null) return List.of();
      List<Node> out = new ArrayList<>();
      for (var r : in.path("entity_vectors")) {
        String name = r.get(0).path("entity_name").asText();
        var n = in.path("nodes").path(name);
        out.add(node(name, n, cosine(v, r.get(1))));
      }
      return out.stream()
          .filter(n -> n.getScore() >= threshold)
          .sorted(Comparator.comparingDouble(Node::getScore).reversed())
          .limit(k)
          .toList();
    }

    Node node(String name, JsonNode n, Double score) {
      int degree = 0;
      for (var e : in.path("edges"))
        if (e.path("src").asText().equals(name) || e.path("tgt").asText().equals(name)) degree++;
      return new Node(
          name,
          name,
          n.path("entity_type").asText(),
          n.path("description").asText(),
          Arrays.asList(n.path("source_id").asText().split("<SEP>")),
          degree,
          score);
    }

    /** Implements the independent reference fixture contract. @author agent */
    public List<Edge> relations(float[] v, int k, double threshold) {
      relationCalls++;
      if (in == null) return List.of();
      List<Edge> out = new ArrayList<>();
      for (var r : in.path("relation_vectors")) {
        String a = r.get(0).path("src_id").asText(), b = r.get(0).path("tgt_id").asText();
        for (var e : in.path("edges"))
          if (Set.of(e.path("src").asText(), e.path("tgt").asText()).equals(Set.of(a, b)))
            out.add(edge(e, cosine(v, r.get(1))));
      }
      return out.stream()
          .filter(e -> e.getScore() >= threshold)
          .sorted(Comparator.comparingDouble(Edge::getScore).reversed())
          .limit(k)
          .toList();
    }

    Edge edge(JsonNode e, Double score) {
      String a = e.path("src").asText(), b = e.path("tgt").asText();
      int rank =
          node(a, in.path("nodes").path(a), null).getDegree()
              + node(b, in.path("nodes").path(b), null).getDegree();
      return new Edge(
          a + "~" + b,
          a,
          b,
          e.path("description").asText(),
          e.path("keywords").asText(),
          e.path("weight").asDouble(),
          Arrays.asList(e.path("source_id").asText().split("<SEP>")),
          rank,
          score);
    }

    /** Implements the independent reference fixture contract. @author agent */
    public List<Chunk> chunks(float[] v, int k, double threshold) {
      chunkCalls++;
      if (in == null) return List.of();
      List<Chunk> out = new ArrayList<>();
      for (var r : in.path("chunk_vectors"))
        out.add(
            new Chunk(
                r.get(0).path("id").asText(),
                r.get(0).path("content").asText(),
                r.get(0).path("file_path").asText(),
                List.of(),
                cosine(v, r.get(1)),
                List.of()));
      return out.stream()
          .filter(c -> c.getScore() >= threshold)
          .sorted(Comparator.comparingDouble(Chunk::getScore).reversed())
          .limit(k)
          .toList();
    }

    /** Implements the independent reference fixture contract. @author agent */
    public List<Edge> adjacent(List<Node> nodes) {
      List<Edge> out = new ArrayList<>();
      for (var e : in.path("edges"))
        if (nodes.stream()
            .anyMatch(
                n ->
                    n.getName().equals(e.path("src").asText())
                        || n.getName().equals(e.path("tgt").asText()))) out.add(edge(e, null));
      return out;
    }

    /** Implements the independent reference fixture contract. @author agent */
    public List<Node> endpoints(List<Edge> edges) {
      LinkedHashSet<String> names = new LinkedHashSet<>();
      for (var e : edges) {
        names.add(e.getSource());
        names.add(e.getTarget());
      }
      return names.stream().map(n -> node(n, in.path("nodes").path(n), null)).toList();
    }

    double cosine(float[] v, JsonNode b) {
      double dot = 0, aNorm = 0, bNorm = 0;
      for (int i = 0; i < v.length; i++) {
        dot += v[i] * b.get(i).asDouble();
        aNorm += v[i] * v[i];
        bNorm += Math.pow(b.get(i).asDouble(), 2);
      }
      return dot / Math.sqrt(aNorm * bNorm);
    }
  }
}
