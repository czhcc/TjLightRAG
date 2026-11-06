package com.trs.app.rag.agent;

import com.trs.app.rag.model.*;
import com.trs.app.rag.retrieval.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.trs.app.rag.agent.ObjectFilterCompiler.*;

/** Explicit read-only tools with native JSON schemas and request-private authority. @author czhcc */
@Component
@RequiredArgsConstructor
public class AgentToolRegistry {
  private final AgentDataRepository data;
  private final RetrievalService retrieval;
  private final RetrievalRepository access;
  private final ModelGateway gateway;
  private final AgentTraceRecorder traces;
  private final AgentToolResultBudget resultBudget;
  private final AgentContextManager contextManager;
  private final JsonMapper json = JsonMapper.builder().build();
  @Value("${rag.agent.max-tool-calls:12}") private int maximum = 12;
  /** Stable protocol identifiers and Chinese names used by trace views. @author czhcc */
  public static final Map<String, String> LABELS = Map.of(
      "get_knowledge_schema", "知识结构查询", "search_knowledge_base", "知识库检索", "find_objects", "对象查询",
      "get_object", "对象详情", "get_object_texts", "关联文本查询", "get_related_objects", "关联对象查询",
      "aggregate_objects", "对象统计", "aggregate_relations", "关系统计", "read_source", "原文查看",
      "read_agent_context", "上下文回查");
  /** Binds callback authority to this server-created run, not tool arguments. @author czhcc */
  public List<ToolCallback> callbacks(AgentRunContext run) {
    return LABELS.keySet().stream().sorted().map(name -> (ToolCallback) new ToolCallback() {
      @Override public ToolDefinition getToolDefinition() {
        return new DefaultToolDefinition(name, LABELS.get(name) + ": " + description(name), json.writeValueAsString(schema(name)));
      }
      @Override public String call(String input) { return invoke(run, name, input); }
    }).toList();
  }
  /** Compares successful calls by tool name and canonical arguments within this run only. @author czhcc */
  public boolean wasSuccessful(AgentRunContext run, String name, String input) {
    try { return run.getSuccessfulTools().containsKey(name + ":" + json.writeValueAsString(canonical(object(json.readValue(input, Map.class))))); }
    catch (RuntimeException invalid) { return false; }
  }
  private String invoke(AgentRunContext run, String name, String input) {
    if (input.length() > 32768) throw new IllegalArgumentException();
    Map<String, Object> p;
    try { p = object(json.readValue(input, Map.class)); }
    catch (RuntimeException invalid) { run.admit(name + ":invalid", maximum); return "{\"status\":\"error\",\"error\":\"invalid_arguments\"}"; }
    String key = name + ":" + json.writeValueAsString(canonical(p));
    run.admit(key, maximum);
    access.check(run.getSnapshot(), run.getActor());
    var pending = run.getPendingCalls().get(name + ":" + input);
    String callId = pending == null || pending.isEmpty() ? "" : pending.removeFirst();
    var span = traces.start(run, name, LABELS.get(name), Map.of("toolCallId", callId, "arguments", p));
    UUID parent = run.getParentSpan(); run.tracing(run.getTraceId(), span.id());
    try {
      var cached = run.getSuccessfulTools().get(key);
      if (cached != null) {
        run.check(); access.check(run.getSnapshot(), run.getActor());
        traces.finish(span, "success", cached, Map.of("reused", true));
        return json.writeValueAsString(cached);
      }
      Object value = switch (name) {
        case "search_knowledge_base" -> search(run, p);
        case "read_source" -> source(run, p);
        case "read_agent_context" -> contextManager.read(run, p);
        default -> executeData(run, name, p);
      };
      if (name.startsWith("aggregate_")) {
        var stat = new LinkedHashMap<>(object(value)); stat.put("exact", true); stat.put("unit", name.equals("aggregate_objects") ? "extracted_object" : "undirected_relation");
        stat.put("evidenceId", "stat-" + (run.getStatistics().size() + 1)); stat.put("distinctBy", "id");
        stat.put("generationId", run.getSnapshot().getGenerationId()); stat.put("knowledgeBaseId", run.getSnapshot().getKnowledgeBaseId());
        stat.put("normalizedFilter", p); stat.put("multipleMembership", name.equals("aggregate_objects") && object(p.getOrDefault("groupBy", Map.of("kind", "NONE"))).get("kind").toString().matches("ATTRIBUTE|RELATED_OBJECT"));
        int page = ((Number) p.getOrDefault("groupPage", 0)).intValue(), size = ((Number) p.getOrDefault("groupSize", 20)).intValue();
        stat.put("hasMore", ((long) page + 1) * size < Long.parseLong(stat.get("groupTotal").toString())); value = stat;
      }
      Object original = value;
      String memoryId = contextManager.archive(run, name, p, original, "success");
      value = resultBudget.project(name, value, run.getToolResultTokens());
      var result = Map.of("status", "success", "generationId", run.getSnapshot().getGenerationId(), "data", value,
          "memoryId", memoryId,
          "evidenceIds", name.startsWith("aggregate_") ? List.of(object(value).get("evidenceId")) : List.of(),
          "warnings", name.startsWith("aggregate_") && Boolean.TRUE.equals(object(value).get("multipleMembership")) ? List.of("Groups may overlap; do not sum group counts.") : List.of());
      String output = json.writeValueAsString(result);
      if (output.length() > 120000) throw new IllegalArgumentException();
      run.check(); access.check(run.getSnapshot(), run.getActor());
      run.getObservations().add(Map.of("tool", name, "arguments", p, "result", value, "memoryId", memoryId));
      if (name.startsWith("aggregate_")) run.getStatistics().add(Map.of("tool", name, "arguments", p, "result", value));
      run.getSuccessfulTools().put(key, result);
      traces.finish(span, "success", Map.of("modelResponse", result, "originalData", original), Map.of("resultTokens", resultBudget.tokens(value), "originalTokens", resultBudget.tokens(original), "truncated", original != value)); return output;
    } catch (IllegalArgumentException invalid) {
      var error = Map.of("status", "error", "error", "invalid_arguments_or_result_too_large", "hint", "Use the declared schema and a smaller page; errors are not zero counts.");
      traces.finish(span, "failed", error, Map.of()); return json.writeValueAsString(error);
    } catch (RuntimeException failure) {
      traces.finish(span, "failed", Map.of("error", "tool_failed"), Map.of()); throw failure;
    } finally { run.tracing(run.getTraceId(), parent); }
  }
  private Object executeData(AgentRunContext run, String name, Map<String, Object> parameters) {
    try { return data.execute(run, name, parameters); }
    catch (org.springframework.dao.InvalidDataAccessApiUsageException translated) {
      // @Repository translates argument validation too; only expose that known validation path as a tool error.
      if (translated.getCause() instanceof IllegalArgumentException invalid) throw invalid;
      throw translated;
    }
  }
  private Object search(AgentRunContext c, Map<String, Object> p) {
    allowed(p, Set.of("query", "mode", "enableRerank", "topK", "chunkTopK"));
    var request = new RetrievalRequest(); request.setQuery(text(p.get("query"), 8000));
    request.setMode(Objects.toString(p.getOrDefault("mode", c.getRequest().getMode())));
    request.setTopK(integer(p, "topK", c.getRequest().getTopK(), 1, 100));
    request.setChunkTopK(integer(p, "chunkTopK", c.getRequest().getChunkTopK(), 1, 100));
    request.setCosineThreshold(c.getRequest().getCosineThreshold()); request.setRelatedChunkNumber(c.getRequest().getRelatedChunkNumber()); request.setChunkSelection(c.getRequest().getChunkSelection());
    request.setMaxEntityTokens(c.getRequest().getMaxEntityTokens()); request.setMaxRelationTokens(c.getRequest().getMaxRelationTokens());
    if (p.containsKey("enableRerank") && !(p.get("enableRerank") instanceof Boolean)) throw new IllegalArgumentException();
    boolean rerank = c.getRequest().isEnableRerank() && !Boolean.FALSE.equals(p.get("enableRerank")) && c.getReranker() != null;
    request.setMaxTotalTokens(Math.max(1024, Math.min(16000, c.getAnswer().getContextTokens() / 3)));
    var result = retrieval.query(c.getSnapshot().getKnowledgeBaseId(), c.getActor(), request, candidates -> {
      c.check(); if (!rerank || candidates.isEmpty()) return candidates.subList(0, Math.min(candidates.size(), request.getChunkTopK()));
      var span = traces.start(c, "rerank", "重排模型", Map.of("model", modelParameters(c.bounded(c.getReranker())), "query", request.getQuery(), "documents", candidates.stream().map(Chunk::getContent).toList()));
      try {
        var scores = gateway.rerank(c.bounded(c.getReranker()), request.getQuery(), candidates.stream().map(Chunk::getContent).toList());
        List<Chunk> selected = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (var score : scores) {
          if (!(score.get("index") instanceof Integer index) || index < 0 || index >= candidates.size() || !seen.add(index)
              || !(score.get("score") instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new ModelFailure("rerank_invalid");
          if (n.doubleValue() >= c.getRequest().getMinRerankScore() && selected.size() < request.getChunkTopK()) {
            var ch = candidates.get(index); selected.add(new Chunk(ch.getId(), ch.getContent(), ch.getFilePath(), ch.getHeadings(), ch.getScore(), ch.getOrigins(), n.doubleValue()));
          }
        }
        c.reranked(); traces.finish(span, "success", scores, Map.of()); return selected;
      } catch (RuntimeException e) { traces.finish(span, "failed", Map.of("error", "rerank_failed"), Map.of()); throw e; }
    }, new RetrievalScope(c.getSnapshot(), c.getDeadline(), c.getKeywordModel(), new RetrievalScope.Observer() {
      @Override public <T> T call(String name, ModelVersion model, Object request, java.util.function.Supplier<T> operation) {
        c.check(); var nested = traces.start(c, name, name.equals("embedding") ? "向量模型" : "检索关键词模型", Map.of("parameters", modelParameters(model), "input", request));
        try { T output = operation.get(); traces.finish(nested, "success", output, Map.of()); return output; }
        catch (RuntimeException e) { traces.finish(nested, "failed", Map.of("error", "retrieval_model_failed"), Map.of()); throw e; }
      }
    }));
    // Namespace each search's citations before merging, so [1] never changes meaning.
    Map<String, String> ids = new HashMap<>(); var refs = new ArrayList<Map<String, Object>>();
    for (var ref : result.getReferences()) {
      String old = ref.get("reference_id").toString(), next = c.getEvidence().reference(ref); ids.put(old, next);
      var copy = new LinkedHashMap<>(ref); copy.put("reference_id", next); refs.add(copy);
    }
    var chunks = new ArrayList<Map<String, Object>>();
    for (var chunk : result.getChunks()) {
      var copy = new LinkedHashMap<>(chunk); copy.put("reference_id", ids.get(chunk.get("reference_id").toString()));
      if (copy.get("reference_ids") instanceof List<?> list) copy.put("reference_ids", list.stream().map(x -> ids.get(x.toString())).toList());
      List<Map<String, Object>> origins = new ArrayList<>();
      if (chunk.get("sources") instanceof List<?> list) for (Object origin : list) {
        var m = object(json.convertValue(origin, Map.class)); var source = Map.<String, Object>of("chunkId", UUID.fromString(chunk.get("chunk_id").toString()),
            "documentId", UUID.fromString(m.get("documentId").toString()), "revisionId", UUID.fromString(m.get("revisionId").toString()), "chunkOrder", m.get("chunkOrder"));
        var registered = new LinkedHashMap<>(m); registered.put("referenceId", c.source(source)); origins.add(registered);
      }
      copy.put("sources", origins); chunks.add(copy); c.getEvidence().chunk(copy);
    }
    String context = result.getContext();
    var matcher = java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(context);
    context = matcher.replaceAll(match -> "[" + ids.getOrDefault(match.group(1), match.group(1)) + "]");
    // The structured observations below carry the canonical citation IDs.
    var remapped = new Result(result.getStatus(), result.getMode(), result.getGenerationId(), result.getEmbeddingVersion(), result.getKeywordVersion(), result.getKeywords(), result.getEntities(), result.getRelations(), chunks, refs, context, result.getMetadata(), c.getRerankStatus().equals("applied"));
    c.getRetrievals().add(remapped);
    return Map.of("entities", result.getEntities(), "relations", result.getRelations(), "chunks", chunks, "references", refs);
  }
  private Object source(AgentRunContext c, Map<String, Object> p) {
    allowed(p, Set.of("referenceId", "maxChars")); String reference = text(p.get("referenceId"), 100);
    var origin = c.getSources().get(reference); if (origin == null) throw new IllegalArgumentException();
    int max = integer(p, "maxChars", 4000, 256, 12000);
    var value = new LinkedHashMap<>(retrieval.source(c.getSnapshot().getKnowledgeBaseId(), c.getSnapshot().getGenerationId(),
        (UUID) origin.get("chunkId"), (UUID) origin.get("documentId"), (UUID) origin.get("revisionId"), ((Number) origin.get("chunkOrder")).intValue(), c.getActor()));
    if (value.get("excerpt") instanceof String excerpt && excerpt.codePointCount(0, excerpt.length()) > max) { value.put("excerpt", excerpt.substring(0, excerpt.offsetByCodePoints(0, max))); value.put("truncated", true); }
    return value;
  }
  /** Captures effective nonsecret provider parameters, including configured extensions. @author czhcc */
  public static Map<String, Object> modelParameters(ModelVersion v) {
    return Map.of("modelId", v.getId(), "baseUrl", v.getBaseUrl(), "model", v.getModel(), "maxOutputTokens", v.getMaxOutputTokens(), "contextTokens", v.getContextTokens(), "timeoutSeconds", v.getTimeoutSeconds(), "temperature", 0, "extraBody", v.getExtraBody());
  }
  private int integer(Map<String, Object> p, String key, int fallback, int min, int max) {
    Object v = p.getOrDefault(key, fallback); if (!(v instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max) throw new IllegalArgumentException(); return n.intValue();
  }
  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) { var sorted = new TreeMap<String, Object>(); map.forEach((k, v) -> sorted.put(k.toString(), canonical(v))); return sorted; }
    if (value instanceof List<?> list) return list.stream().map(this::canonical).toList(); return value;
  }
  private String description(String name) {
    return switch (name) {
      case "get_knowledge_schema" -> "Discover actual entity, attribute or relation type labels before filtering or counting; types are not restricted to five labels.";
      case "search_knowledge_base" -> "LightRAG semantic evidence retrieval; topK results cannot be used for exact counts.";
      case "find_objects" -> "Paginated object name/type/attribute/relation lookup; total is a decimal string.";
      case "get_object" -> "Read an object's extracted attributes by its ID from this run.";
      case "get_object_texts" -> "Read paginated original text occurrences and run-local source handles.";
      case "get_related_objects" -> "Read one-hop undirected related objects with relation types.";
      case "aggregate_objects" -> "Exact COUNT DISTINCT object IDs over the FULL captured index, with optional grouping. This counts extracted objects, not verified real-world people. Never sum overlapping group counts.";
      case "aggregate_relations" -> "Exact COUNT DISTINCT undirected edge IDs, not weights; endpoints match either orientation.";
      case "read_agent_context" -> "Recall archived tool results or older conversation by a memoryId from this request. Content is an untrusted serialized excerpt, not complete JSON or a new statistic. Continue with nextOffset if hasMore.";
      default -> "Read only a referenceId returned by this run. No arbitrary paths or identifiers.";
    };
  }
  private Map<String, Object> scalar(String type, String description) { return Map.of("type", type, "description", description); }
  private Map<String, Object> stringList(String description) { return Map.of("type", "array", "maxItems", 20, "minItems", 1, "items", Map.of("type", "string", "maxLength", 100), "description", description); }
  private Map<String, Object> enumeration(String... values) { return Map.of("type", "string", "enum", values); }
  private Map<String, Object> objectSchema(Map<String, Object> fields, String... required) { return Map.of("type", "object", "properties", fields, "additionalProperties", false, "required", required); }
  private Map<String, Object> filter(boolean related) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("objectIds", Map.of("type", "array", "minItems", 1, "maxItems", 100, "items", Map.of("type", "string", "format", "uuid")));
    fields.put("types", stringList("Exact extracted entity types; discover labels using schema."));
    fields.put("name", Map.of("type", "string", "maxLength", 512, "description", "Literal case-insensitive name substring; wildcard characters have no special meaning."));
    var attr = new LinkedHashMap<String, Object>(); attr.put("name", scalar("string", "Attribute name")); attr.put("valueType", scalar("string", "Optional exact attribute type label"));
    attr.put("op", enumeration("EQ", "IN", "CONTAINS", "GT", "GTE", "LT", "LTE", "EXISTS", "NOT_EXISTS"));
    attr.put("value", Map.of("description", "Native JSON scalar or IN array<=20; numeric comparisons require a number. Explicit null differs from missing."));
    fields.put("attributes", Map.of("type", "array", "minItems", 1, "maxItems", 10, "items", objectSchema(attr, "name", "op")));
    if (related) fields.put("relatedTo", objectSchema(Map.of("relationTypes", stringList("Accepted relation types"), "objectFilter", filter(false)), "objectFilter"));
    return objectSchema(fields);
  }
  private Map<String, Object> schema(String name) {
    var fields = new LinkedHashMap<String, Object>(); String[] required = {};
    switch (name) {
      case "get_knowledge_schema" -> { fields.put("section", enumeration("ENTITY_TYPES", "ATTRIBUTES", "RELATION_TYPES")); fields.put("entityTypes", stringList("Optional entity types, not used with RELATION_TYPES")); required = new String[]{"section"}; }
      case "search_knowledge_base" -> {
        fields.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 8000, "description", "Standalone search question resolved from conversation history"));
        fields.put("mode", enumeration("naive", "local", "global", "hybrid", "mix")); fields.put("enableRerank", scalar("boolean", "Cannot override user's disabled reranking"));
        fields.put("topK", Map.of("type", "integer", "minimum", 1, "maximum", 100)); fields.put("chunkTopK", fields.get("topK")); required = new String[]{"query"};
      }
      case "find_objects", "aggregate_objects" -> fields.put("filter", filter(true));
      case "get_object", "get_object_texts", "get_related_objects" -> { fields.put("objectId", Map.of("type", "string", "format", "uuid")); required = new String[]{"objectId"}; }
      case "aggregate_relations" -> {
        fields.put("relationTypes", stringList("Types of edges to count")); fields.put("endpointA", filter(false)); fields.put("endpointB", filter(false)); fields.put("groupBy", enumeration("NONE", "RELATION_TYPE"));
      }
      case "read_source" -> { fields.put("referenceId", scalar("string", "Opaque handle returned in this run")); fields.put("maxChars", Map.of("type", "integer", "minimum", 256, "maximum", 12000)); required = new String[]{"referenceId"}; }
      case "read_agent_context" -> {
        fields.put("memoryId", scalar("string", "Opaque memory handle from this request only"));
        fields.put("offset", Map.of("type", "integer", "minimum", 0, "maximum", 2000000));
        fields.put("maxChars", Map.of("type", "integer", "minimum", 64, "maximum", 4000)); required = new String[]{"memoryId"};
      }
      default -> throw new IllegalArgumentException();
    }
    if (name.equals("get_related_objects")) { fields.put("relationTypes", stringList("Optional relation types")); fields.put("targetFilter", filter(false)); }
    if (name.equals("aggregate_objects")) fields.put("groupBy", objectSchema(Map.of("kind", enumeration("NONE", "TYPE", "ATTRIBUTE", "RELATED_OBJECT"),
        "attributeName", scalar("string", "Required with ATTRIBUTE"), "relationTypes", stringList("Used with RELATED_OBJECT"), "targetFilter", filter(false), "includeMissing", scalar("boolean", "Include missing values, default true")), "kind"));
    if (name.startsWith("aggregate_")) {
      fields.put("groupPage", Map.of("type", "integer", "minimum", 0)); fields.put("groupSize", Map.of("type", "integer", "minimum", 1, "maximum", 100)); fields.put("order", enumeration("COUNT_DESC", "COUNT_ASC", "KEY_ASC"));
    } else if (Set.of("get_knowledge_schema", "find_objects", "get_object_texts", "get_related_objects").contains(name)) {
      fields.put("page", Map.of("type", "integer", "minimum", 0)); fields.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 100));
    }
    return objectSchema(fields, required);
  }
}
