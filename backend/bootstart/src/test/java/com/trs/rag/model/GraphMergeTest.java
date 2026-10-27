package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.index.*;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Candidate comparisons against frozen, actual upstream outputs. @author agent */
class GraphMergeTest {
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void entityAndRelationCandidatesMatchAllEightFrozenUpstreamCases() throws Exception {
    var parser = new ExtractionParser();
    int count = 0;
    try (var in = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
      var cases = json.readTree(in).path("cases");
      for (var c : cases) {
        String family = c.path("family").asText();
        if (!Set.of("entity", "relation").contains(family)) continue;
        List<String> fields = new ArrayList<>();
        c.path("input").path("record").forEach(f -> fields.add(f.asText()));
        var actual = parser.parse(String.join("<|#|>", fields) + "\n<|COMPLETE|>");
        var expected = c.path("expected");
        if (expected.isNull()) {
          assertTrue(
              actual.getEntities().isEmpty() && actual.getRelations().isEmpty(),
              c.path("id").asText());
        } else if (family.equals("entity")) {
          var e = actual.getEntities().getFirst();
          assertEquals(expected.path("entity_name").asText(), e.getName());
          assertEquals(expected.path("entity_type").asText(), e.getType());
          assertEquals(expected.path("description").asText(), e.getDescription());
        } else {
          var r = actual.getRelations().getFirst();
          assertEquals(expected.path("src_id").asText(), r.getSource());
          assertEquals(expected.path("tgt_id").asText(), r.getTarget());
          assertEquals(expected.path("keywords").asText(), r.getKeywords());
          assertEquals(expected.path("description").asText(), r.getDescription());
          assertEquals(expected.path("weight").asDouble(), r.getWeight());
        }
        count++;
      }
    }
    assertEquals(8, count);
  }

  @Test
  void immutableContributionsMatchUpstreamFinalNodeAndEdgeSnapshots() throws Exception {
    var gateway = mock(ModelGateway.class);
    when(gateway.embed(any(), anyString())).thenReturn(new float[] {1, 0, 0});
    var tokenizer = mock(RagTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
    var graphs = new GraphAssembler(gateway, tokenizer);
    UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
    var a =
        new Extraction(
            List.of(new Extraction.Entity("Alice", "person", "Alice uses LightRAG.")),
            List.of(
                new Extraction.Relation("Alice", "LightRAG", "uses", "Alice uses LightRAG.", 1)));
    var b =
        new Extraction(
            List.of(new Extraction.Entity("Alice", "person", "Alice builds graphs.")),
            List.of(
                new Extraction.Relation("Alice", "LightRAG", "uses", "Alice uses LightRAG.", 1)));
    Map<UUID, Extraction> contributions = new LinkedHashMap<>();
    contributions.put(c1, a);
    contributions.put(c1, a);
    contributions.put(c2, b);
    var result = graphs.assemble(contributions, null, null, () -> {});
    try (var in = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
      for (var c : json.readTree(in).path("cases")) {
        if (c.path("family").asText().equals("node_merge")) {
          var last = c.path("expected").path("snapshots").get(2);
          var alice =
              result.getEntities().stream()
                  .filter(e -> e.getName().equals("Alice"))
                  .findFirst()
                  .orElseThrow();
          assertEquals(last.path("description").asText(), alice.getDescription());
          assertEquals(last.path("entity_type").asText(), alice.getType());
          assertEquals(Set.of(c1, c2), new HashSet<>(alice.getSources()));
        }
        if (c.path("family").asText().equals("edge_merge")) {
          var last = c.path("expected").path("snapshots").get(2);
          var relation = result.getRelations().getFirst();
          assertEquals(last.path("weight").asDouble(), relation.getWeight());
          assertEquals(last.path("description").asText(), relation.getDescription());
          assertEquals(last.path("keywords").asText(), relation.getKeywords());
        }
      }
    }
  }

  @Test
  void descriptionDedupAvoidsUnnecessaryCallsAndTypeTiesAreStable() {
    var gateway = mock(ModelGateway.class);
    when(gateway.embed(any(), anyString())).thenReturn(new float[] {1, 0, 0});
    var tokenizer = mock(RagTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
    var graphs = new GraphAssembler(gateway, tokenizer);
    assertEquals("fact", graphs.describe("entity", "A", List.of("fact", "fact"), null, () -> {}));
    verifyNoInteractions(gateway);
    Map<UUID, Extraction> c = new LinkedHashMap<>();
    c.put(
        UUID.randomUUID(),
        new Extraction(List.of(new Extraction.Entity("A", "person", "fact")), List.of()));
    c.put(
        UUID.randomUUID(),
        new Extraction(List.of(new Extraction.Entity("A", "concept", "other")), List.of()));
    assertEquals(
        "concept", graphs.assemble(c, null, null, () -> {}).getEntities().getFirst().getType());
  }

  @Test
  void summariesUsePinnedPromptAndBoundedModelOutput() {
    var gateway = mock(ModelGateway.class);
    when(gateway.chat(any(), any())).thenReturn("A merged summary.");
    var tokenizer = mock(RagTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
    var graphs = new GraphAssembler(gateway, tokenizer);
    var model =
        new ModelVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CHAT",
            "https://example.com/v1",
            "chat",
            "",
            0,
            16000,
            2000,
            5);
    var desc = java.util.stream.IntStream.range(0, 10).mapToObj(i -> "fact " + i).toList();
    assertEquals("A merged summary.", graphs.describe("entity", "Alice", desc, model, () -> {}));
    verify(gateway)
        .chat(
            eq(model),
            argThat(
                messages ->
                    messages.getFirst().get("content").contains("Knowledge Graph Specialist")
                        && messages.getFirst().get("content").contains("fact 9")));
  }

  @Test
  void pinnedGleaningResponsesAndRenderedPromptsMatchFrozenPipeline() throws Exception {
    var parser = new ExtractionParser();
    try (var in = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
      for (var c : json.readTree(in).path("cases")) {
        if (!c.path("family").asText().equals("extraction_pipeline")) continue;
        var input = c.path("input");
        var merged =
            parser.glean(
                parser.parse(input.path("model_responses").get(0).asText()),
                parser.parse(input.path("model_responses").get(1).asText()));
        var expected = c.path("expected").path("results").get(0);
        assertEquals(expected.path("nodes").size(), merged.getEntities().size());
        for (var e : merged.getEntities()) {
          var node = expected.path("nodes").path(e.getName()).get(0);
          assertEquals(node.path("description").asText(), e.getDescription());
          assertEquals(node.path("entity_type").asText(), e.getType());
        }
        assertEquals(expected.path("edges").size(), merged.getRelations().size());
        String content = input.path("chunks").path("c1").path("content").asText();
        var calls = c.path("expected").path("model_calls");
        assertEquals(
            calls.get(0).path("system_prompt").asText(),
            PromptTemplates.load("extract-system.txt"));
        assertEquals(
            calls.get(0).path("prompt").asText(),
            PromptTemplates.load("extract-user.txt")
                .replace("{heading_context_block}", "")
                .replace("{input_text}", content));
        assertEquals(
            calls.get(1).path("prompt").asText(),
            PromptTemplates.load("glean-user.txt").replace("{input_text}", content));
      }
    }
  }
}
