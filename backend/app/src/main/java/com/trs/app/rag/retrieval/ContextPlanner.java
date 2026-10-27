package com.trs.app.rag.retrieval;

import static com.trs.app.rag.retrieval.PythonJson.object;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Four-stage budget planning with whole-record prefixes and final exact prompt
 * verification. @author agent
 */
@Component
@RequiredArgsConstructor
public class ContextPlanner {
  public static final int OUTPUT_RESERVE = 1024, MESSAGE_OVERHEAD = 16;
  private final RagTokenizer tokenizer;
  private final RetrievalAlgorithms algorithms;

  /**
   * Renders the pinned answer system prompt for accounting only; never calls an answering
   * model. @author agent
   */
  public String systemPrompt(String context) {
    return RetrievalPrompts.format(
        RetrievalPrompts.load("response.txt"),
        Map.of("context_data", context, "response_type", "Multiple Paragraphs", "user_prompt", ""));
  }

  private String nodes(List<Node> nodes) {
    return String.join(
        "\n",
        nodes.stream()
            .map(
                n ->
                    PythonJson.render(
                        object(
                            "entity",
                            n.getName(),
                            "type",
                            n.getType(),
                            "description",
                            n.getDescription())))
            .toList());
  }

  private String edges(List<Edge> edges) {
    return String.join(
        "\n",
        edges.stream()
            .map(
                e ->
                    PythonJson.render(
                        object(
                            "entity1",
                            e.getSource(),
                            "entity2",
                            e.getTarget(),
                            "description",
                            e.getDescription())))
            .toList());
  }

  private String context(GraphSlice graph, ReferenceResult refs) {
    String references =
        String.join(
            "\n",
            refs.getReferences().stream()
                .map(r -> "[" + r.get("reference_id") + "] " + r.get("file_path"))
                .toList());
    return RetrievalPrompts.format(
        RetrievalPrompts.load("context.txt"),
        Map.of(
            "entities_str",
            nodes(graph.getNodes()),
            "relations_str",
            edges(graph.getEdges()),
            "text_chunks_str",
            algorithms.renderChunks(refs.getChunks()),
            "reference_list_str",
            references));
  }

  private int total(String query, String context) {
    return tokenizer.encode(systemPrompt(context)).length
        + tokenizer.encode(query).length
        + OUTPUT_RESERVE
        + MESSAGE_OVERHEAD;
  }

  /**
   * Retains serialized prefixes and fits graph overhead before choosing graph-related
   * chunks. @author agent
   */
  public GraphSlice graph(String query, RetrievalRequest request, Raw raw) {
    List<Node> nodes =
        new ArrayList<>(prefix(raw.getNodes(), this::nodes, request.getMaxEntityTokens()));
    List<Edge> edges =
        new ArrayList<>(prefix(raw.getEdges(), this::edges, request.getMaxRelationTokens()));
    var empty = new ReferenceResult(List.of(), List.of());
    while (total(query, context(new GraphSlice(nodes, edges), empty))
        > request.getMaxTotalTokens()) {
      if (!edges.isEmpty()) edges.removeLast();
      else if (!nodes.isEmpty()) nodes.removeLast();
      else throw new RetrievalFailure("retrieval_budget_too_small", 400);
    }
    return new GraphSlice(List.copyOf(nodes), List.copyOf(edges));
  }

  /**
   * Performs approximate then exact reference-aware prefix truncation, including the full
   * prompt. @author agent
   */
  public ContextPlan finish(
      String query, RetrievalRequest request, GraphSlice graph, List<Chunk> candidates) {
    int available =
        request.getMaxTotalTokens()
            - total(query, context(graph, new ReferenceResult(List.of(), List.of())));
    List<Chunk> kept =
        new ArrayList<>(
            prefix(
                candidates,
                rows ->
                    String.join(
                        "\n",
                        rows.stream()
                            .map(
                                c -> {
                                  var row = object("content", c.getContent());
                                  if (!c.getHeadings().isEmpty())
                                    row.put("content_headings", c.getHeadings());
                                  return PythonJson.render(row);
                                })
                            .toList()),
                Math.max(0, available)));
    ReferenceResult refs = algorithms.references(kept);
    String context = context(graph, refs);
    while (!kept.isEmpty() && total(query, context) > request.getMaxTotalTokens()) {
      kept.removeLast();
      refs = algorithms.references(kept);
      context = context(graph, refs);
    }
    if (graph.getNodes().isEmpty() && graph.getEdges().isEmpty() && kept.isEmpty()) context = "";
    Map<String, Integer> budget = new LinkedHashMap<>();
    budget.put("entityTokens", tokenizer.encode(nodes(graph.getNodes())).length);
    budget.put("relationTokens", tokenizer.encode(edges(graph.getEdges())).length);
    budget.put("chunkTokens", tokenizer.encode(algorithms.renderChunks(refs.getChunks())).length);
    budget.put("contextTokens", tokenizer.encode(context).length);
    budget.put("promptTokens", tokenizer.encode(systemPrompt(context)).length);
    budget.put("queryTokens", tokenizer.encode(query).length);
    budget.put("reservedOutputTokens", OUTPUT_RESERVE);
    budget.put("messageOverheadTokens", MESSAGE_OVERHEAD);
    budget.put("totalTokens", total(query, context));
    budget.put("maxTotalTokens", request.getMaxTotalTokens());
    if (total(query, context) > request.getMaxTotalTokens())
      throw new RetrievalFailure("retrieval_budget_too_small", 400);
    return new ContextPlan(context, refs.getChunks(), refs.getReferences(), budget);
  }

  /** Retains only complete prefix records under the exact joined serialization. @author agent */
  public <T> List<T> prefix(List<T> values, Function<List<T>, String> render, int limit) {
    if (limit <= 0) return List.of();
    int low = 0, high = values.size();
    while (low < high) {
      int mid = (low + high + 1) / 2;
      if (tokenizer.encode(render.apply(values.subList(0, mid))).length <= limit) low = mid;
      else high = mid - 1;
    }
    while (low > 0 && tokenizer.encode(render.apply(values.subList(0, low))).length > limit) low--;
    return values.subList(0, low);
  }
}
