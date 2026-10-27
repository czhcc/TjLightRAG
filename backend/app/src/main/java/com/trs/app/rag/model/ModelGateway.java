package com.trs.app.rag.model;

import com.trs.app.rag.ingestion.RagTokenizer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Spring AI model adapters for bounded OpenAI-compatible wire protocols. @author agent */
@Component
@RequiredArgsConstructor
public class ModelGateway {
  private final SecretVault vault;
  private final EndpointPolicy policy;
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /**
   * Creates a version-scoped Spring AI ChatModel with no global mutable provider settings. @author
   * agent
   */
  public ChatModel chatModel(ModelVersion v) {
    return prompt -> {
      List<Map<String, String>> messages =
          prompt.getInstructions().stream()
              .map(m -> Map.of("role", m.getMessageType().getValue(), "content", m.getText()))
              .toList();
      var result =
          post(
              v,
              "/chat/completions",
              Map.of(
                  "model",
                  v.getModel(),
                  "messages",
                  messages,
                  "max_tokens",
                  v.getMaxOutputTokens(),
                  "temperature",
                  0,
                  "stream",
                  false));
      var choices = result.path("choices");
      if (choices.size() != 1 || !choices.get(0).path("finish_reason").asText().equals("stop"))
        throw new ModelFailure("model_truncated_or_invalid");
      String text = choices.get(0).path("message").path("content").asText("");
      if (text.isBlank() || text.length() > 2000000)
        throw new ModelFailure("model_empty_or_oversize");
      return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    };
  }

  /** Calls chat with an explicit input/output context guard and bounded response. @author agent */
  public String chat(ModelVersion v, List<Map<String, String>> messages) {
    if (!v.getKind().equals("CHAT")) throw new IllegalArgumentException();
    int tokens =
        messages.stream().mapToInt(m -> tokenizer.encode(m.get("content")).length + 8).sum() + 16;
    if (tokens + v.getMaxOutputTokens() > v.getContextTokens())
      throw new ModelFailure("model_context_exceeded");
    List<Message> list =
        messages.stream()
            .<Message>map(
                m ->
                    switch (m.get("role")) {
                      case "system" -> new SystemMessage(m.get("content"));
                      case "assistant" -> new AssistantMessage(m.get("content"));
                      default -> new UserMessage(m.get("content"));
                    })
            .toList();
    return chatModel(v).call(new Prompt(list)).getResult().getOutput().getText();
  }

  /** Creates a cancellable Spring AI streaming adapter for the captured version. @author agent */
  public StreamingChatModel streamingChatModel(ModelVersion v) {
    return prompt -> ModelStream.open(v, prompt, vault, policy, client);
  }

  /** Streams actual provider deltas; interruption disposes the HTTP subscription. @author agent */
  public void stream(
      ModelVersion v,
      List<Map<String, String>> messages,
      java.util.function.Consumer<String> delta) {
    if (!v.getKind().equals("CHAT")) throw new IllegalArgumentException();
    int tokens =
        messages.stream().mapToInt(m -> tokenizer.encode(m.get("content")).length + 8).sum() + 16;
    if (tokens + v.getMaxOutputTokens() > v.getContextTokens())
      throw new ModelFailure("model_context_exceeded");
    List<Message> list =
        messages.stream()
            .<Message>map(
                m ->
                    switch (m.get("role")) {
                      case "system" -> new SystemMessage(m.get("content"));
                      case "assistant" -> new AssistantMessage(m.get("content"));
                      default -> new UserMessage(m.get("content"));
                    })
            .toList();
    try {
      streamingChatModel(v).stream(new Prompt(list))
          .doOnNext(response -> delta.accept(response.getResult().getOutput().getText()))
          .blockLast();
    } catch (ModelFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      if (Thread.currentThread().isInterrupted()) throw new ModelFailure("model_interrupted");
      throw new ModelFailure("model_stream_unavailable");
    }
  }

  /** Creates a version-scoped Spring AI EmbeddingModel. @author agent */
  public EmbeddingModel embeddingModel(ModelVersion v) {
    return new EmbeddingModel() {
      /**
       * Sends one embedding request and validates every returned index and dimension. @author agent
       */
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        var response =
            post(
                v,
                "/embeddings",
                Map.of(
                    "model",
                    v.getModel(),
                    "input",
                    request.getInstructions(),
                    "encoding_format",
                    "float"));
        var data = response.path("data");
        if (!data.isArray() || data.size() != request.getInstructions().size())
          throw new ModelFailure("embedding_invalid");
        List<Embedding> rows = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (var item : data) {
          int index = item.path("index").asInt(-1);
          var vector = item.path("embedding");
          if (index < 0
              || index >= data.size()
              || !seen.add(index)
              || vector.size() != v.getDimensions())
            throw new ModelFailure("embedding_dimension_mismatch");
          float[] values = new float[vector.size()];
          double norm = 0;
          for (int i = 0; i < values.length; i++) {
            if (!vector.get(i).isNumber()) throw new ModelFailure("embedding_invalid");
            values[i] = (float) vector.get(i).asDouble();
            if (!Float.isFinite(values[i])) throw new ModelFailure("embedding_invalid");
            norm += (double) values[i] * values[i];
          }
          if (norm == 0) throw new ModelFailure("embedding_invalid");
          rows.add(new Embedding(values, index));
        }
        rows.sort(Comparator.comparing(Embedding::getIndex));
        return new EmbeddingResponse(rows);
      }

      /** Adapts a Spring AI document to the explicit text request. @author agent */
      @Override
      public float[] embed(Document document) {
        return embed(document.getText());
      }
    };
  }

  /** Generates one finite nonzero vector using the captured model space. @author agent */
  public float[] embed(ModelVersion v, String text) {
    if (!v.getKind().equals("EMBEDDING")) throw new IllegalArgumentException();
    if (tokenizer.encode(text).length > v.getContextTokens())
      throw new ModelFailure("embedding_context_exceeded");
    return embeddingModel(v).embed(text);
  }

  /**
   * Embeds only the texts used by one query in a single validated Spring AI request. @author agent
   */
  public List<float[]> embedBatch(ModelVersion version, List<String> texts) {
    if (!version.getKind().equals("EMBEDDING") || texts.isEmpty() || texts.size() > 16)
      throw new IllegalArgumentException();
    for (String text : texts)
      if (tokenizer.encode(text).length > version.getContextTokens())
        throw new ModelFailure("embedding_context_exceeded");
    return embeddingModel(version).embed(texts);
  }

  /**
   * Calls a bounded compatible rerank endpoint with strict index and score validation. @author
   * agent
   */
  public List<Map<String, Object>> rerank(ModelVersion v, String query, List<String> documents) {
    if (!v.getKind().equals("RERANK") || documents.isEmpty() || documents.size() > 500)
      throw new IllegalArgumentException();
    var response =
        post(
            v,
            "/rerank",
            Map.of(
                "model",
                v.getModel(),
                "query",
                query,
                "documents",
                documents,
                "top_n",
                documents.size()));
    var results = response.path("results");
    if (!results.isArray() || results.isEmpty() || results.size() > documents.size())
      throw new ModelFailure("rerank_invalid");
    Set<Integer> seen = new HashSet<>();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var r : results) {
      int index = r.path("index").isIntegralNumber() ? r.path("index").asInt(-1) : -1;
      double score =
          r.path("relevance_score").isNumber()
              ? r.path("relevance_score").asDouble(Double.NaN)
              : Double.NaN;
      if (index < 0 || index >= documents.size() || !seen.add(index) || !Double.isFinite(score))
        throw new ModelFailure("rerank_invalid");
      rows.add(Map.of("index", index, "score", score));
    }
    return rows;
  }

  private JsonNode post(ModelVersion v, String suffix, Map<String, ?> body) {
    policy.validate(v.getBaseUrl());
    java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> requestFuture = null;
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(v.getBaseUrl() + suffix))
              .timeout(Duration.ofSeconds(v.getTimeoutSeconds()))
              .header("Content-Type", "application/json");
      String key = vault.decrypt(v.getCipher(), v.getProfileId());
      if (!key.isEmpty()) builder.header("Authorization", "Bearer " + key);
      requestFuture =
          client.sendAsync(
              builder
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          json.writeValueAsString(body), StandardCharsets.UTF_8))
                  .build(),
              info -> new BoundedBody());
      var response =
          requestFuture.get(v.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
      if (response.statusCode() < 200 || response.statusCode() > 299)
        throw new ModelFailure(
            response.statusCode() == 429 ? "model_rate_limited" : "model_http_error");
      return json.readTree(response.body());
    } catch (ModelFailure e) {
      throw e;
    } catch (java.util.concurrent.TimeoutException e) {
      throw new ModelFailure("model_timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelFailure("model_interrupted");
    } catch (Exception e) {
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ModelFailure m) throw m;
      throw new ModelFailure("model_unavailable");
    } finally {
      if (requestFuture != null && !requestFuture.isDone()) requestFuture.cancel(true);
    }
  }

  /**
   * Bounded subscriber prevents untrusted provider payloads from exhausting memory. @author agent
   */
  private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private final java.util.concurrent.CompletableFuture<byte[]> result =
        new java.util.concurrent.CompletableFuture<>();
    private java.util.concurrent.Flow.Subscription subscription;

    /** Exposes bounded body completion. @author agent */
    @Override
    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return result;
    }

    /** Requests one body segment at a time. @author agent */
    @Override
    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    /** Cancels immediately on the four MiB response ceiling. @author agent */
    @Override
    public void onNext(List<java.nio.ByteBuffer> buffers) {
      for (var b : buffers) {
        if ((long) bytes.size() + b.remaining() > 4 * 1024 * 1024) {
          subscription.cancel();
          result.completeExceptionally(new ModelFailure("model_response_oversize"));
          return;
        }
        byte[] part = new byte[b.remaining()];
        b.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    /** Propagates transport failures to the sanitized boundary. @author agent */
    @Override
    public void onError(Throwable e) {
      result.completeExceptionally(e);
    }

    /** Completes with the fully bounded body. @author agent */
    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
