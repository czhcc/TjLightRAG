package com.trs.app.rag.retrieval;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import jakarta.validation.Validator;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Query-local orchestration; model calls never hold a database transaction or mutable active
 * pointer. @author agent
 */
@Service
public class RetrievalService {
  private final RetrievalRepository repository;
  private final ModelConfigurationService models;
  private final ModelGateway gateway;
  private final RagTokenizer tokenizer;
  private final RetrievalAlgorithms algorithms;
  private final ContextPlanner planner;
  private final Validator validator;
  private final Semaphore slots;
  private final int timeout;

  /**
   * Captures bounded local admission and query deadlines alongside immutable model
   * adapters. @author agent
   */
  public RetrievalService(
      RetrievalRepository repository,
      ModelConfigurationService models,
      ModelGateway gateway,
      RagTokenizer tokenizer,
      RetrievalAlgorithms algorithms,
      ContextPlanner planner,
      Validator validator,
      @Value("${rag.retrieval.max-concurrency:4}") int concurrency,
      @Value("${rag.retrieval.timeout-seconds:120}") int timeout) {
    if (concurrency < 1 || concurrency > 16 || timeout < 1 || timeout > 180)
      throw new IllegalArgumentException("Invalid retrieval limits");
    this.repository = repository;
    this.models = models;
    this.gateway = gateway;
    this.tokenizer = tokenizer;
    this.algorithms = algorithms;
    this.planner = planner;
    this.validator = validator;
    this.slots = new Semaphore(concurrency);
    this.timeout = timeout;
  }

  /**
   * Returns graph/vector evidence and exact budget diagnostics without generating an
   * answer. @author agent
   */
  public Result query(UUID kb, String actor, RetrievalRequest request) {
    return query(kb, actor, request, java.util.function.UnaryOperator.identity());
  }

  /**
   * Applies a request-scoped chunk selector before final truncation and references. @author agent
   */
  public Result query(
      UUID kb,
      String actor,
      RetrievalRequest request,
      java.util.function.UnaryOperator<List<Chunk>> selector) {
    if (!slots.tryAcquire()) throw new RetrievalFailure("retrieval_busy", 429);
    try {
      if (request == null
          || !validator.validate(request).isEmpty()
          || !Double.isFinite(request.getCosineThreshold())) throw new IllegalArgumentException();
      long started = System.nanoTime(), deadline = started + timeout * 1000000000L;
      String query = request.getQuery().strip();
      if (tokenizer.encode(query).length > 2048)
        throw new RetrievalFailure("retrieval_query_too_long", 400);
      Snapshot snapshot = repository.capture(kb, actor);
      if (!snapshot.getTokenizer().equals(tokenizer.getName()))
        throw new RetrievalFailure("retrieval_tokenizer_unsupported", 409);
      ModelVersion embedding = models.version(snapshot.getEmbeddingVersion());
      if (embedding.getDimensions() != snapshot.getDimensions()
          || !embedding.getKind().equals("EMBEDDING"))
        throw new RetrievalFailure("retrieval_vector_space_mismatch", 409);
      boolean provided =
          request.getHighLevelKeywords() != null || request.getLowLevelKeywords() != null;
      Keywords keywords = new Keywords(true, List.of(), List.of());
      boolean auto = false, fallback = false;
      if (!request.getMode().equals("naive")) {
        if (provided) {
          keywords =
              new Keywords(
                  true,
                  clean(request.getHighLevelKeywords()),
                  clean(request.getLowLevelKeywords()));
        } else {
          auto = true;
          String prompt =
              RetrievalPrompts.format(
                  RetrievalPrompts.load("keywords.txt"), Map.of("query", query));
          String output =
              gateway.chat(
                  bounded(models.version(snapshot.getKeywordVersion()), deadline),
                  List.of(Map.of("role", "user", "content", prompt)));
          keywords = algorithms.keywords(output);
          if (!keywords.isValid()) throw new RetrievalFailure("retrieval_invalid_keywords", 502);
          if (keywords.getHigh().size() > 32
              || keywords.getLow().size() > 32
              || keywords.getHigh().stream().anyMatch(v -> v.length() > 256)
              || keywords.getLow().stream().anyMatch(v -> v.length() > 256))
            throw new RetrievalFailure("retrieval_keyword_limit", 502);
          if (keywords.getHigh().isEmpty()
              && keywords.getLow().isEmpty()
              && query.codePointCount(0, query.length()) < 50) {
            keywords = new Keywords(true, List.of(), List.of(query));
            fallback = true;
          }
        }
      }
      repository.check(snapshot, actor);
      boolean
          low =
              Set.of("local", "hybrid", "mix").contains(request.getMode())
                  && !keywords.getLow().isEmpty(),
          high =
              Set.of("global", "hybrid", "mix").contains(request.getMode())
                  && !keywords.getHigh().isEmpty();
      boolean needQuery =
          Set.of("naive", "mix").contains(request.getMode())
              || (request.getRelatedChunkNumber() > 0
                  && request.getChunkSelection().equals("VECTOR")
                  && (low || high));
      Map<String, String> needed = new LinkedHashMap<>();
      if (needQuery) needed.put("query", query);
      if (low) needed.put("low", String.join(", ", keywords.getLow()));
      if (high) needed.put("high", String.join(", ", keywords.getHigh()));
      List<String> distinct = new ArrayList<>(new LinkedHashSet<>(needed.values()));
      Map<String, float[]> vectors = new HashMap<>();
      if (!distinct.isEmpty()) {
        List<float[]> embedded = gateway.embedBatch(bounded(embedding, deadline), distinct);
        for (var need : needed.entrySet())
          vectors.put(need.getKey(), embedded.get(distinct.indexOf(need.getValue())));
      }
      checkDeadline(deadline);
      SearchBackend backend =
          new SearchBackend() {
            /** Scoped entity branch. @author agent */
            public List<Node> entities(float[] v, int k, double t) {
              return repository.entities(snapshot, actor, v, k, t);
            }

            /** Scoped relation branch. @author agent */
            public List<Edge> relations(float[] v, int k, double t) {
              return repository.relations(snapshot, actor, v, k, t);
            }

            /** Scoped vector branch. @author agent */
            public List<Chunk> chunks(float[] v, int k, double t) {
              return repository.chunks(snapshot, actor, v, k, t);
            }

            /** Scoped adjacency expansion. @author agent */
            public List<Edge> adjacent(List<Node> nodes) {
              return repository.adjacent(snapshot, actor, nodes);
            }

            /** Scoped endpoint expansion. @author agent */
            public List<Node> endpoints(List<Edge> edges) {
              return repository.endpoints(snapshot, actor, edges);
            }
          };
      Raw raw = algorithms.search(backend, request, vectors, low, high);
      GraphSlice graph = planner.graph(query, request, raw);
      if (graph.getNodes().stream().mapToLong(n -> n.getSourceIds().size()).sum()
              + graph.getEdges().stream().mapToLong(e -> e.getSourceIds().size()).sum()
          > 50000) throw new RetrievalFailure("retrieval_resource_limit", 413);
      List<String> entityChunks =
          select(
              snapshot,
              actor,
              graph.getNodes().stream().map(Node::getSourceIds).toList(),
              Set.of(),
              request,
              vectors.get("query"));
      List<String> relationChunks =
          select(
              snapshot,
              actor,
              graph.getEdges().stream().map(Edge::getSourceIds).toList(),
              new HashSet<>(entityChunks),
              request,
              vectors.get("query"));
      List<String> vectorChunks = raw.getVectors().stream().map(Chunk::getId).toList();
      List<String> merged =
          algorithms.roundRobin(List.of(vectorChunks, entityChunks, relationChunks), v -> v);
      Map<String, Double> scores = new HashMap<>();
      for (var chunk : raw.getVectors()) scores.put(chunk.getId(), chunk.getScore());
      List<Chunk> hydrated = repository.hydrate(snapshot, actor, merged, scores);
      checkDeadline(deadline);
      ContextPlan plan = planner.finish(query, request, graph, selector.apply(hydrated));
      for (var chunk : plan.getChunks()) {
        String id = chunk.get("chunk_id").toString();
        List<String> paths = new ArrayList<>();
        if (vectorChunks.contains(id)) paths.add("vector");
        if (entityChunks.contains(id)) paths.add("entity");
        if (relationChunks.contains(id)) paths.add("relation");
        chunk.put("retrievalPaths", paths);
      }
      repository.check(snapshot, actor);
      checkDeadline(deadline);
      Map<String, Object> metadata =
          PythonJson.object(
              "branches",
              raw.getBranches(),
              "embeddingCalls",
              distinct.isEmpty() ? 0 : 1,
              "embeddingPurposes",
              new ArrayList<>(needed.keySet()),
              "keywordSource",
              auto
                  ? (fallback ? "model_with_query_fallback" : "model")
                  : provided ? "provided" : "not_used",
              "vectorStrategy",
              "exact",
              "tokenizer",
              tokenizer.getName(),
              "promptVersion",
              RetrievalPrompts.VERSION,
              "budget",
              plan.getBudget(),
              "counts",
              PythonJson.object(
                  "rawEntities",
                  raw.getNodes().size(),
                  "rawRelations",
                  raw.getEdges().size(),
                  "vectorChunks",
                  raw.getVectors().size(),
                  "entitiesAfterBudget",
                  graph.getNodes().size(),
                  "relationsAfterBudget",
                  graph.getEdges().size(),
                  "entityChunks",
                  entityChunks.size(),
                  "relationChunks",
                  relationChunks.size(),
                  "mergedChunks",
                  merged.size(),
                  "returnedChunks",
                  plan.getChunks().size()),
              "elapsedMs",
              (System.nanoTime() - started) / 1000000);
      return new Result(
          graph.getNodes().isEmpty() && graph.getEdges().isEmpty() && plan.getChunks().isEmpty()
              ? "empty"
              : "success",
          request.getMode(),
          snapshot.getGenerationId(),
          snapshot.getEmbeddingVersion(),
          request.getMode().equals("naive") ? null : snapshot.getKeywordVersion(),
          keywords,
          graph.getNodes(),
          graph.getEdges(),
          plan.getChunks(),
          plan.getReferences(),
          plan.getContext(),
          metadata,
          false);
    } catch (DataAccessException
        | org.springframework.transaction.TransactionException unavailable) {
      throw new RetrievalFailure("retrieval_storage_unavailable", 503);
    } finally {
      slots.release();
    }
  }

  private List<String> clean(List<String> value) {
    return value == null
        ? List.of()
        : value.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  private List<String> select(
      Snapshot snapshot,
      String actor,
      List<List<String>> sources,
      Set<String> excluded,
      RetrievalRequest request,
      float[] queryVector) {
    if (request.getRelatedChunkNumber() == 0) return List.of();
    var groups = algorithms.sourceGroups(sources, excluded);
    if (groups.isEmpty()) return List.of();
    if (request.getChunkSelection().equals("WEIGHT"))
      return algorithms.weightedPolling(groups, request.getRelatedChunkNumber());
    List<String> candidates = groups.stream().flatMap(List::stream).toList();
    return repository.related(
        snapshot,
        actor,
        candidates,
        queryVector,
        algorithms.quota(request.getRelatedChunkNumber(), groups.size()));
  }

  private void checkDeadline(long deadline) {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
      throw new RetrievalFailure("retrieval_timeout", 504);
  }

  private ModelVersion bounded(ModelVersion version, long deadline) {
    checkDeadline(deadline);
    int seconds = Math.max(1, (int) ((deadline - System.nanoTime()) / 1000000000L));
    return new ModelVersion(
        version.getId(),
        version.getProfileId(),
        version.getKind(),
        version.getBaseUrl(),
        version.getModel(),
        version.getCipher(),
        version.getDimensions(),
        version.getContextTokens(),
        version.getMaxOutputTokens(),
        Math.min(version.getTimeoutSeconds(), seconds));
  }

  /**
   * Reads a reference excerpt with the same authorization and safe storage-failure
   * boundary. @author agent
   */
  public Map<String, Object> source(
      UUID kb, UUID generation, UUID chunk, UUID document, UUID revision, int order, String actor) {
    try {
      return repository.source(kb, generation, chunk, document, revision, order, actor);
    } catch (DataAccessException
        | org.springframework.transaction.TransactionException unavailable) {
      throw new RetrievalFailure("retrieval_storage_unavailable", 503);
    }
  }
}
