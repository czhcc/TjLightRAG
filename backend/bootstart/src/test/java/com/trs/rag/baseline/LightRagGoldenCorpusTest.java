package com.trs.rag.baseline;

import java.util.*;
import java.security.MessageDigest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Validates the upstream corpus and reusable Java comparison boundary, not production RAG.
 * @author agent
 */
class LightRagGoldenCorpusTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHA = "aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a";

    private JsonNode corpus() throws Exception {
        try (var input = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
            assertNotNull(input, "Generate the upstream corpus before running baseline tests");
            return JSON.readTree(input);
        }
    }

    @Test
    void provenanceAndAlgorithmCoverageAreExplicit() throws Exception {
        var root = corpus();
        assertEquals(SHA, root.path("upstream_commit").asText());
        assertEquals("unicode-codepoint-test-v1", root.path("tokenizer").asText());
        Set<String> ids = new HashSet<>(), families = new HashSet<>();
        for (var c : root.path("cases")) {
            assertTrue(ids.add(c.path("id").asText()), "Duplicate case ID");
            assertTrue(c.has("input") && c.has("expected"));
            assertFalse(c.path("source").asText().isBlank());
            families.add(c.path("family").asText());
        }
        assertEquals(Set.of("chunk", "entity", "relation", "keywords", "sources", "descriptions",
                "quota", "references", "budget", "search", "merge_chunks", "context", "cache", "summary", "node_merge", "edge_merge", "extraction_pipeline", "rerank"), families);
        assertTrue(ids.size() >= 35);
    }

    @Test
    void corpusChecksumMatchesProvenance() throws Exception {
        try (var corpusInput = getClass().getResourceAsStream("/lightrag-baseline/corpus.json");
             var provenanceInput = getClass().getResourceAsStream("/lightrag-baseline/provenance.json")) {
            assertNotNull(corpusInput); assertNotNull(provenanceInput);
            var provenance = JSON.readTree(provenanceInput);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpusInput.readAllBytes()));
            assertEquals(provenance.path("corpus_sha256").asText(), digest);
            assertEquals(provenance.path("case_count").asInt(), corpus().path("cases").size());
            assertEquals(SHA, provenance.path("upstream_commit").asText());
        }
    }

    /** Exposes each recorded output as a separately reported contract check.
     * @return corpus invariant checks
     * @throws Exception invalid corpus
     * @author agent
     */
    @TestFactory
    Stream<DynamicTest> corpusInvariants() throws Exception {
        var cases = new ArrayList<JsonNode>();
        corpus().path("cases").forEach(cases::add);
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.path("id").asText(), () -> {
            var expected = c.path("expected");
            switch (c.path("family").asText()) {
                case "chunk" -> {
                    if (expected.has("error")) { assertEquals("ValueError", expected.path("error").asText()); break; }
                    String content = c.path("input").path("content").asText();
                    int order = 0;
                    for (var chunk : expected.path("chunks")) {
                        assertEquals(order++, chunk.path("chunk_order_index").asInt());
                        assertTrue(chunk.path("tokens").asInt() <= c.path("input").path("size").asInt());
                        var span = chunk.path("_source_span");
                        if (!span.isMissingNode()) {
                            int start = content.offsetByCodePoints(0, span.path("start").asInt());
                            int end = content.offsetByCodePoints(0, span.path("end").asInt());
                            assertEquals(content.substring(start, end), chunk.path("content").asText());
                        }
                    }
                    assertEquals(content.codePoints().boxed().toList(), JSON.convertValue(expected.path("tokens"), List.class));
                }
                case "sources" -> {
                    var seen = new HashSet<String>();
                    for (var id : expected) assertTrue(seen.add(id.asText()) && !id.asText().isBlank());
                }
                case "references" -> {
                    Set<String> references = new HashSet<>();
                    int index = 1;
                    for (var ref : expected.path("references")) {
                        assertEquals(Integer.toString(index++), ref.path("reference_id").asText());
                        assertTrue(references.add(ref.path("reference_id").asText()));
                    }
                    for (var chunk : expected.path("chunks")) {
                        var id = chunk.path("reference_id").asText();
                        assertTrue(id.isEmpty() || references.contains(id));
                    }
                }
                case "quota" -> {
                    int max = c.path("input").path("max").asInt(), groups = c.path("input").path("groups").asInt();
                    assertEquals(max <= 0 || groups <= 0 ? 0 : Math.max(1, max * groups / 2), expected.asInt());
                }
                case "budget" -> {
                    String joined = String.join(c.path("input").path("separator").asText(), JSON.convertValue(expected, List.class));
                    assertTrue(joined.codePointCount(0, joined.length()) <= c.path("input").path("limit").asInt());
                }
                case "edge_merge" -> {
                    var snapshots = expected.path("snapshots");
                    assertEquals(1.0, snapshots.get(0).path("weight").asDouble());
                    assertEquals(1.0, snapshots.get(1).path("weight").asDouble(), "reprocess must not inflate weight");
                    assertEquals(2.0, snapshots.get(2).path("weight").asDouble());
                }
                case "node_merge" -> {
                    var snapshots = expected.path("snapshots");
                    assertEquals(snapshots.get(0), snapshots.get(1), "reprocess must be idempotent");
                    assertTrue(snapshots.get(2).path("source_id").asText().contains("c2"));
                }
                case "extraction_pipeline" -> {
                    assertEquals(2, expected.path("model_calls").size(), "initial extraction plus gleaning");
                    var nodes = expected.path("results").get(0).path("nodes");
                    assertTrue(nodes.has("Alice") && nodes.has("张三"));
                    assertEquals(1, expected.path("results").get(0).path("edges").size());
                }
                case "search" -> {
                    if (expected.has("error")) { assertEquals("RuntimeError", expected.path("error").asText()); break; }
                    String mode = c.path("input").path("mode").asText();
                    var calls = expected.path("calls");
                    assertEquals(Set.of("local", "hybrid", "mix").contains(mode) ? 1 : 0, calls.path("entities").size());
                    assertEquals(Set.of("global", "hybrid", "mix").contains(mode) ? 1 : 0, calls.path("relations").size());
                    assertEquals(Set.of("naive", "mix").contains(mode) ? 1 : 0, calls.path("chunks").size());
                    if (mode.equals("naive")) {
                        // Independent exact cosine check over the fixture input, not a production vector store.
                        var vectors = new ArrayList<JsonNode>();
                        c.path("input").path("chunk_vectors").forEach(vectors::add);
                        vectors.sort(Comparator.comparingDouble((JsonNode v) -> cosine(v.get(1))).reversed());
                        for (int i = 0; i < 2; i++) assertEquals(vectors.get(i).get(0).path("id").asText(), expected.path("result").get(i).path("chunk_id").asText());
                    }
                }
                default -> assertFalse(expected.isMissingNode());
            }
        }));
    }
    private static double cosine(JsonNode vector) {
        double norm = 0;
        for (var component : vector) norm += component.asDouble() * component.asDouble();
        return vector.get(0).asDouble() / Math.sqrt(norm); // query = [1, 0, 0]
    }
}
