package com.trs.rag.query;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.KnowledgeBaseService;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import com.trs.app.rag.query.*;
import com.trs.app.rag.retrieval.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;

/** Answer boundaries independent of supplier wording and database fixtures. @author agent */
class QueryServiceTest {
  final UUID kb = UUID.randomUUID(), generation = UUID.randomUUID(), modelId = UUID.randomUUID();
  final RetrievalService retrieval = mock(RetrievalService.class);
  final RetrievalRepository repository = mock(RetrievalRepository.class);
  final ModelConfigurationService models = mock(ModelConfigurationService.class);
  final ModelGateway gateway = mock(ModelGateway.class);
  final RagTokenizer tokenizer = mock(RagTokenizer.class);
  final KnowledgeBaseService access = mock(KnowledgeBaseService.class);
  final ContextPlanner planner = mock(ContextPlanner.class);
  QueryService service;
  QueryRequest request;

  @BeforeEach
  void setup() {
    when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
    when(tokenizer.getName()).thenReturn("cl100k_base");
    when(models.snapshot()).thenReturn(Map.of("answer", modelId));
    when(models.version(modelId))
        .thenReturn(
            new ModelVersion(
                modelId,
                UUID.randomUUID(),
                "CHAT",
                "https://example.com/v1",
                "answer",
                "",
                0,
                16000,
                2048,
                10));
    when(planner.systemPrompt(anyString())).thenAnswer(i -> "system:" + i.getArgument(0));
    service =
        new QueryService(
            retrieval, repository, models, gateway, tokenizer, planner, access, 2, 180);
    request = new QueryRequest();
    request.setQuery("问题🙂");
  }

  Result evidence(String status) {
    return new Result(
        status,
        "mix",
        generation,
        UUID.randomUUID(),
        null,
        new Keywords(true, List.of(), List.of()),
        List.of(),
        List.of(),
        List.of(),
        List.of(Map.of("reference_id", "1", "file_path", "doc.md")),
        status.equals("empty") ? "" : "evidence",
        Map.of(),
        false);
  }

  void returns(Result result) {
    when(retrieval.query(eq(kb), eq("admin"), any(), any())).thenReturn(result);
  }

  @Test
  void previewUsesExactlyTheAnswerPromptAndNeverCallsAnswerModel() {
    returns(evidence("success"));
    request.setPreview("prompt");
    var out = service.query(kb, "admin", request);
    assertEquals("system:evidence", out.getPrompt().getFirst().get("content"));
    assertNull(out.getAnswer());
    assertEquals("disabled", out.getCacheStatus());
    verifyNoInteractions(gateway);
  }

  @Test
  void noEvidenceRefusesWithoutModelAndWithoutInventingReferences() {
    returns(evidence("empty"));
    var out = service.query(kb, "admin", request);
    assertEquals("empty", out.getStatus());
    assertTrue(out.getAnswer().contains("依据"));
    assertFalse(out.isLlmGenerated());
    verifyNoInteractions(gateway);
  }

  @Test
  void answersAreNotCachedAndOnlyKnownCitationsAreResolved() {
    returns(evidence("success"));
    when(gateway.chat(any(), any())).thenReturn("事实[1]，不存在[99]。");
    var out = service.query(kb, "admin", request);
    assertEquals(List.of("1"), out.getCitedReferenceIds());
    assertEquals(List.of("99"), out.getUnknownReferenceIds());
    service.query(kb, "admin", request);
    verify(gateway, times(2)).chat(any(), any());
    assertTrue(out.getPrompt().isEmpty());
  }

  @Test
  void missingAnswerBindingFailsExplicitlyAndDoesNotCallModels() {
    when(models.snapshot()).thenReturn(Map.of());
    var e = assertThrows(RetrievalFailure.class, () -> service.query(kb, "admin", request));
    assertEquals("query_answer_model_missing", e.getCode());
    verifyNoInteractions(gateway);
  }

  @Test
  void rerankPreservesProviderOrderFiltersScoresAndRejectsBadIndices() {
    var chunk = new Chunk("a", "one", "d", List.of(), null, List.of());
    var second = new Chunk("b", "two", "d", List.of(), null, List.of());
    var v =
        new ModelVersion(
            modelId,
            UUID.randomUUID(),
            "RERANK",
            "https://example.com/v1",
            "r",
            "",
            0,
            16000,
            100,
            10);
    when(gateway.rerank(any(), anyString(), any()))
        .thenReturn(List.of(Map.of("index", 1, "score", .9), Map.of("index", 0, "score", .1)));
    var selected = service.rerank(v, "q", List.of(chunk, second), 20, .5);
    assertEquals("b", selected.getFirst().getId());
    assertEquals(.9, selected.getFirst().getRerankScore());
    assertNull(second.getRerankScore());
    when(gateway.rerank(any(), anyString(), any()))
        .thenReturn(List.of(Map.of("index", 9, "score", .9)));
    assertThrows(ModelFailure.class, () -> service.rerank(v, "q", List.of(chunk), 20, .5));
  }

  @Test
  void rerankFailureIsVisibleRatherThanSilentlyFallingBack() {
    when(models.snapshot()).thenReturn(Map.of("answer", modelId, "rerank", modelId));
    request.setEnableRerank(true);
    when(retrieval.query(eq(kb), eq("admin"), any(), any()))
        .thenAnswer(
            i -> {
              UnaryOperator<List<Chunk>> transform = i.getArgument(3);
              transform.apply(List.of(new Chunk("a", "one", "d", List.of(), null, List.of())));
              return evidence("success");
            });
    when(gateway.rerank(any(), anyString(), any())).thenThrow(new ModelFailure("rerank_invalid"));
    assertThrows(ModelFailure.class, () -> service.query(kb, "admin", request));
    verify(gateway, never()).chat(any(), any());
  }

  @Test
  void rerankMatchesTheFrozenUpstreamProductionOutputIncludingScores() throws Exception {
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    try (var input = getClass().getResourceAsStream("/lightrag-baseline/corpus.json")) {
      var cases = json.readTree(input).path("cases");
      for (var c : cases)
        if (c.path("id").asText().equals("rerank-fixed-response")) {
          List<Chunk> chunks = new ArrayList<>();
          for (var row : c.path("input").path("chunks"))
            chunks.add(
                new Chunk(
                    row.path("chunk_id").asText(),
                    row.path("content").asText(),
                    row.path("file_path").asText(),
                    List.of(),
                    null,
                    List.of()));
          List<Map<String, Object>> response = new ArrayList<>();
          for (var row : c.path("input").path("model_response"))
            response.add(
                Map.of(
                    "index",
                    row.path("index").asInt(),
                    "score",
                    row.path("relevance_score").asDouble()));
          when(gateway.rerank(any(), anyString(), any())).thenReturn(response);
          var actual =
              service.rerank(
                  models.version(modelId), c.path("input").path("query").asText(), chunks, 100, 0);
          List<Map<String, Object>> projection =
              actual.stream()
                  .map(
                      row ->
                          PythonJson.object(
                              "chunk_id",
                              row.getId(),
                              "content",
                              row.getContent(),
                              "file_path",
                              row.getFilePath(),
                              "rerank_score",
                              row.getRerankScore()))
                  .toList();
          assertEquals(c.path("expected"), json.valueToTree(projection));
          assertTrue(
              new RetrievalAlgorithms()
                  .references(actual)
                  .getChunks()
                  .getFirst()
                  .containsKey("rerank_score"));
          return;
        }
      fail("Frozen rerank fixture missing");
    }
  }
}
