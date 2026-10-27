package com.trs.app.rag.query;

import com.trs.app.rag.*;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import com.trs.app.rag.retrieval.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Single-turn answer orchestration with no answer-cache reads or writes. @author agent */
@Service
public class QueryService {
  private final RetrievalService retrieval;
  private final RetrievalRepository repository;
  private final ModelConfigurationService models;
  private final ModelGateway gateway;
  private final RagTokenizer tokenizer;
  private final ContextPlanner planner;
  private final KnowledgeBaseService access;
  private final Semaphore slots;
  private final int timeout;

  /** Shares admission limits across synchronous and streaming generation. @author agent */
  public QueryService(
      RetrievalService retrieval,
      RetrievalRepository repository,
      ModelConfigurationService models,
      ModelGateway gateway,
      RagTokenizer tokenizer,
      ContextPlanner planner,
      KnowledgeBaseService access,
      @Value("${rag.query.max-concurrency:4}") int concurrency,
      @Value("${rag.query.timeout-seconds:180}") int timeout) {
    if (concurrency < 1 || concurrency > 16 || timeout < 1 || timeout > 300)
      throw new IllegalArgumentException();
    this.retrieval = retrieval;
    this.repository = repository;
    this.models = models;
    this.gateway = gateway;
    this.tokenizer = tokenizer;
    this.planner = planner;
    this.access = access;
    this.slots = new Semaphore(concurrency);
    this.timeout = timeout;
  }

  /** Produces an answer or explicit preview using the same model-sized context. @author agent */
  public QueryResult query(UUID kb, String actor, QueryRequest request) {
    return execute(kb, actor, request, null);
  }

  /**
   * Emits metadata, references and incremental provider text before terminal validation. @author
   * agent
   */
  public QueryResult stream(
      UUID kb, String actor, QueryRequest request, BiConsumer<String, Object> events) {
    return execute(kb, actor, request, events);
  }

  private QueryResult execute(
      UUID kb, String actor, QueryRequest request, BiConsumer<String, Object> events) {
    if (!slots.tryAcquire()) throw new RetrievalFailure("query_busy", 429);
    long deadline = System.nanoTime() + timeout * 1000000000L;
    try {
      access.requireAccess(kb, actor, false);
      if (request == null
          || !Set.of("answer", "context", "prompt")
              .contains(Objects.toString(request.getPreview(), ""))
          || !Double.isFinite(request.getMinRerankScore())
          || request.getMinRerankScore() < 0
          || request.getMinRerankScore() > 1) throw new IllegalArgumentException();
      Map<String, UUID> bindings = models.snapshot();
      UUID answerId = bindings.get("answer"),
          rerankId = request.isEnableRerank() ? bindings.get("rerank") : null;
      if (answerId == null) throw new RetrievalFailure("query_answer_model_missing", 409);
      ModelVersion answer = models.version(answerId);
      if (!answer.getKind().equals("CHAT"))
        throw new RetrievalFailure("query_answer_model_invalid", 409);
      ModelVersion reranker = rerankId == null ? null : models.version(rerankId);
      // Stage-four budgets reserve 1024 output tokens and 16 message tokens. Charge any
      // additional configured output plus the gateway's conservative 32-message accounting.
      int reserve = Math.max(0, answer.getMaxOutputTokens() - ContextPlanner.OUTPUT_RESERVE) + 16;
      int original = request.getMaxTotalTokens();
      int effective = Math.min(original, answer.getContextTokens()) - reserve;
      if (effective < 1024) throw new RetrievalFailure("query_budget_too_small", 400);
      request.setMaxTotalTokens(effective);
      String[] rerankState = {
        request.isEnableRerank()
            ? (reranker == null ? "not_configured" : "no_candidates")
            : "disabled"
      };
      Result result;
      try {
        result =
            retrieval.query(
                kb,
                actor,
                request,
                candidates -> {
                  check(deadline);
                  List<Chunk> selected = candidates;
                  if (reranker != null && !candidates.isEmpty()) {
                    selected =
                        rerank(
                            bounded(reranker, deadline),
                            request.getQuery().strip(),
                            candidates,
                            request.getChunkTopK(),
                            request.getMinRerankScore());
                    rerankState[0] = "applied";
                  }
                  // Match process_chunks_unified: rerank -> score filter -> chunk_top_k -> budget.
                  return selected.subList(0, Math.min(selected.size(), request.getChunkTopK()));
                });
      } finally {
        request.setMaxTotalTokens(original);
      }
      result =
          new Result(
              result.getStatus(),
              result.getMode(),
              result.getGenerationId(),
              result.getEmbeddingVersion(),
              result.getKeywordVersion(),
              result.getKeywords(),
              result.getEntities(),
              result.getRelations(),
              result.getChunks(),
              result.getReferences(),
              result.getContext(),
              result.getMetadata(),
              rerankState[0].equals("applied"));
      String system = planner.systemPrompt(result.getContext());
      List<Map<String, String>> messages =
          List.of(
              Map.of("role", "system", "content", system),
              Map.of("role", "user", "content", request.getQuery().strip()));
      int actual =
          messages.stream().mapToInt(m -> tokenizer.encode(m.get("content")).length + 8).sum()
              + 16
              + answer.getMaxOutputTokens();
      if (actual > Math.min(original, answer.getContextTokens()))
        throw new RetrievalFailure("query_budget_too_small", 400);
      Map<String, Object> diagnostic = new LinkedHashMap<>(result.getMetadata());
      diagnostic.put(
          "answerBudget",
          Map.of(
              "totalTokens",
              actual,
              "maxTotalTokens",
              original,
              "modelContextTokens",
              answer.getContextTokens(),
              "reservedOutputTokens",
              answer.getMaxOutputTokens(),
              "tokenizer",
              tokenizer.getName()));
      result =
          new Result(
              result.getStatus(),
              result.getMode(),
              result.getGenerationId(),
              result.getEmbeddingVersion(),
              result.getKeywordVersion(),
              result.getKeywords(),
              result.getEntities(),
              result.getRelations(),
              result.getChunks(),
              result.getReferences(),
              result.getContext(),
              diagnostic,
              result.isReranked());
      var snapshot =
          new Snapshot(
              kb,
              result.getGenerationId(),
              result.getEmbeddingVersion(),
              result.getKeywordVersion(),
              0,
              "");
      repository.check(snapshot, actor);
      check(deadline);
      if (events != null) {
        events.accept(
            "metadata",
            Map.of(
                "requestId",
                request.getRequestId(),
                "generationId",
                result.getGenerationId(),
                "answerVersion",
                answerId,
                "rerankStatus",
                rerankState[0],
                "cacheStatus",
                "disabled",
                "mode",
                result.getMode(),
                "totalTokens",
                actual));
        events.accept("references", result);
      }
      if (!request.getPreview().equals("answer"))
        return new QueryResult(
            "preview",
            null,
            false,
            answerId,
            rerankId,
            rerankState[0],
            "disabled",
            result,
            request.getPreview().equals("prompt") ? messages : List.of(),
            List.of(),
            List.of());
      String text;
      boolean generated = !result.getStatus().equals("empty");
      if (!generated) {
        text =
            request
                    .getQuery()
                    .codePoints()
                    .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                ? "没有找到足够的依据，无法回答这个问题。"
                : "I could not find sufficient evidence to answer this question.";
        if (events != null) events.accept("delta", Map.of("text", text));
      } else if (events == null) {
        text = gateway.chat(bounded(answer, deadline), messages);
      } else {
        StringBuilder collected = new StringBuilder();
        gateway.stream(
            bounded(answer, deadline),
            messages,
            delta -> {
              check(deadline);
              repository.check(snapshot, actor);
              if (collected.length() + delta.length() > 2000000)
                throw new ModelFailure("model_response_oversize");
              collected.append(delta);
              events.accept("delta", Map.of("text", delta));
            });
        text = collected.toString();
      }
      repository.check(snapshot, actor);
      check(deadline);
      Set<String> available = new HashSet<>();
      result.getReferences().forEach(r -> available.add(r.get("reference_id").toString()));
      Set<String> cited = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
      var matcher = Pattern.compile("\\[(\\d{1,8})\\]").matcher(text);
      while (matcher.find())
        (available.contains(matcher.group(1)) ? cited : unknown).add(matcher.group(1));
      return new QueryResult(
          generated ? "success" : "empty",
          text,
          generated,
          answerId,
          rerankId,
          rerankState[0],
          "disabled",
          result,
          List.of(),
          List.copyOf(cited),
          List.copyOf(unknown));
    } catch (DataAccessException | org.springframework.transaction.TransactionException failure) {
      throw new RetrievalFailure("query_storage_unavailable", 503);
    } finally {
      slots.release();
    }
  }

  /**
   * Preserves provider order and source objects, rejecting invalid indices without
   * fallback. @author agent
   */
  public List<Chunk> rerank(
      ModelVersion v, String query, List<Chunk> candidates, int topK, double minimum) {
    // The bounded provider protocol caps candidates; reject rather than silently dropping evidence.
    if (candidates.size() > 500) throw new RetrievalFailure("query_rerank_candidate_limit", 413);
    int tokens =
        tokenizer.encode(query).length
            + candidates.stream().mapToInt(c -> tokenizer.encode(c.getContent()).length + 8).sum();
    if (tokens > v.getContextTokens()) throw new ModelFailure("rerank_context_exceeded");
    List<Map<String, Object>> rows =
        gateway.rerank(v, query, candidates.stream().map(Chunk::getContent).toList());
    if (rows == null || rows.isEmpty() || rows.size() > candidates.size())
      throw new ModelFailure("rerank_invalid");
    Set<Integer> seen = new HashSet<>();
    List<Chunk> selected = new ArrayList<>();
    for (var row : rows) {
      if (!(row.get("index") instanceof Integer index)
          || index < 0
          || index >= candidates.size()
          || !seen.add(index)
          || !(row.get("score") instanceof Number score)
          || !Double.isFinite(score.doubleValue())) throw new ModelFailure("rerank_invalid");
      if (score.doubleValue() >= minimum && selected.size() < topK) {
        var chunk = candidates.get(index);
        selected.add(
            new Chunk(
                chunk.getId(),
                chunk.getContent(),
                chunk.getFilePath(),
                chunk.getHeadings(),
                chunk.getScore(),
                chunk.getOrigins(),
                score.doubleValue()));
      }
    }
    return selected;
  }

  private void check(long deadline) {
    if (Thread.currentThread().isInterrupted()) throw new RetrievalFailure("query_cancelled", 409);
    if (System.nanoTime() >= deadline) throw new RetrievalFailure("query_timeout", 504);
  }

  private ModelVersion bounded(ModelVersion v, long deadline) {
    check(deadline);
    int seconds = Math.max(1, (int) ((deadline - System.nanoTime()) / 1000000000L));
    return new ModelVersion(
        v.getId(),
        v.getProfileId(),
        v.getKind(),
        v.getBaseUrl(),
        v.getModel(),
        v.getCipher(),
        v.getDimensions(),
        v.getContextTokens(),
        v.getMaxOutputTokens(),
        Math.min(seconds, v.getTimeoutSeconds()));
  }
}
