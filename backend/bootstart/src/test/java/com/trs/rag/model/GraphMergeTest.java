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
    void sameNameDifferentTypesKeepSeparateIdsAndSources() {
        var gateway = mock(ModelGateway.class);
        when(gateway.embed(any(), anyString())).thenReturn(new float[] {1, 0, 0});
        var tokenizer = mock(RagTokenizer.class);
        when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
        var parser = new ExtractionParser();
        var a = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
        a.accept("""
        {"entities":[{"name":"长安","type":"地","description":"城市","attributes":[]},{"name":"长安","type":"网","description":"公司","attributes":[]}],
         "relations":[{"source":{"name":"长安","type":"地"},"target":{"name":"长安","type":"网"},"type":"位于","keywords":"城市,企业","description":"企业位于城市"}]}
        """);
        var b = new Extraction(List.of(new Extraction.Entity("长安", "地", "历史城市")), List.of());
        var graph = new GraphAssembler(gateway, tokenizer)
                .assemble(new LinkedHashMap<>(Map.of(c1, a.finish(), c2, b)), null, null, () -> {});
        assertEquals(2, graph.getEntities().size());
        var city = graph.getEntities().stream()
                .filter(n -> n.getType().equals("地"))
                .findFirst()
                .orElseThrow();
        var org = graph.getEntities().stream()
                .filter(n -> n.getType().equals("网"))
                .findFirst()
                .orElseThrow();
        assertNotEquals(city.getId(), org.getId());
        assertEquals(Set.of(c1, c2), new HashSet<>(city.getSources()));
        assertEquals(List.of(c1), org.getSources());
        assertEquals(
                Set.of(city.getId(), org.getId()),
                Set.of(
                        graph.getRelations().getFirst().getSource(),
                        graph.getRelations().getFirst().getTarget()));
    }

    @Test
    void mergesAttributeValuesAndRelationshipTypesWithoutCountingAChunkTwice() {
        var gateway = mock(ModelGateway.class);
        when(gateway.embed(any(), anyString())).thenReturn(new float[] {1, 0, 0});
        var tokenizer = mock(RagTokenizer.class);
        when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
        var first = new Extraction(
                List.of(
                        new Extraction.Entity("张三", "人", "工程师", Map.of("职业", "工程师")),
                        new Extraction.Entity("甲公司", "网", "公司")),
                List.of(
                        new Extraction.Relation("张三", "甲公司", "工作", "任职于公司", 1, "任职"),
                        new Extraction.Relation("张三", "甲公司", "创办", "创办公司", 1, "创办")));
        var second = new Extraction(List.of(new Extraction.Entity("张三", "人", "研究员", Map.of("职业", "研究员"))), List.of());
        Map<UUID, Extraction> chunks = new LinkedHashMap<>();
        chunks.put(UUID.randomUUID(), first);
        chunks.put(UUID.randomUUID(), second);
        var graph = new GraphAssembler(gateway, tokenizer).assemble(chunks, null, null, () -> {});
        var person = graph.getEntities().stream()
                .filter(e -> e.getName().equals("张三"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Set.of("工程师", "研究员"),
                new HashSet<>(person.getAttributes().stream()
                        .filter(a -> a.getName().equals("职业"))
                        .map(a -> a.getValue().asText())
                        .toList()));
        assertTrue(person.getDescription().contains("职业"));
        var edge = graph.getRelations().getFirst();
        assertEquals(Set.of("任职", "创办"), new HashSet<>(edge.getTypes()));
        assertEquals(1, edge.getWeight());
    }

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
                            actual.getEntities().isEmpty()
                                    && actual.getRelations().isEmpty(),
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
        var a = new Extraction(
                List.of(new Extraction.Entity("Alice", "person", "Alice uses LightRAG.")),
                List.of(new Extraction.Relation("Alice", "LightRAG", "uses", "Alice uses LightRAG.", 1)));
        var b = new Extraction(
                List.of(new Extraction.Entity("Alice", "person", "Alice builds graphs.")),
                List.of(new Extraction.Relation("Alice", "LightRAG", "uses", "Alice uses LightRAG.", 1)));
        Map<UUID, Extraction> contributions = new LinkedHashMap<>();
        contributions.put(c1, a);
        contributions.put(c1, a);
        contributions.put(c2, b);
        var result = graphs.assemble(contributions, null, null, () -> {});
        try (var in = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
            for (var c : json.readTree(in).path("cases")) {
                if (c.path("family").asText().equals("node_merge")) {
                    var last = c.path("expected").path("snapshots").get(2);
                    var alice = result.getEntities().stream()
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
        c.put(UUID.randomUUID(), new Extraction(List.of(new Extraction.Entity("A", "person", "fact")), List.of()));
        c.put(UUID.randomUUID(), new Extraction(List.of(new Extraction.Entity("A", "concept", "other")), List.of()));
        assertEquals(2, graphs.assemble(c, null, null, () -> {}).getEntities().size());
    }

    @Test
    void summariesUsePinnedPromptAndBoundedModelOutput() {
        var gateway = mock(ModelGateway.class);
        when(gateway.chat(any(), any())).thenReturn("A merged summary.");
        var tokenizer = mock(RagTokenizer.class);
        when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
        var graphs = new GraphAssembler(gateway, tokenizer);
        var model = new ModelVersion(
                UUID.randomUUID(), UUID.randomUUID(), "CHAT", "https://example.com/v1", "chat", "", 0, 16000, 2000, 5);
        var desc = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> "fact " + i)
                .toList();
        assertEquals("A merged summary.", graphs.describe("entity", "Alice", desc, model, () -> {}));
        verify(gateway)
                .chat(
                        eq(model),
                        argThat(messages -> messages.getFirst().get("content").contains("Knowledge Graph Specialist")
                                && messages.getFirst().get("content").contains("fact 9")));
    }

    @Test
    void pinnedGleaningResponsesAndRenderedPromptsMatchFrozenPipeline() throws Exception {
        var parser = new ExtractionParser();
        try (var in = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
            for (var c : json.readTree(in).path("cases")) {
                if (!c.path("family").asText().equals("extraction_pipeline")) continue;
                var input = c.path("input");
                var merged = parser.glean(
                        parser.parse(input.path("model_responses").get(0).asText()),
                        parser.parse(input.path("model_responses").get(1).asText()));
                var expected = c.path("expected").path("results").get(0);
                assertEquals(expected.path("nodes").size(), merged.getEntities().size());
                for (var e : merged.getEntities()) {
                    var node = expected.path("nodes").path(e.getName()).get(0);
                    assertEquals(node.path("description").asText(), e.getDescription());
                    assertEquals(node.path("entity_type").asText(), e.getType());
                }
                assertEquals(
                        expected.path("edges").size(), merged.getRelations().size());
                String content = input.path("chunks").path("c1").path("content").asText();
                var calls = c.path("expected").path("model_calls");
                assertEquals(calls.get(0).path("system_prompt").asText(), PromptTemplates.load("extract-system.txt"));
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
