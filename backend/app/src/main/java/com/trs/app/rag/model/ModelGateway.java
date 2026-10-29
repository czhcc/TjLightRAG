package com.trs.app.rag.model;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import com.trs.app.rag.ingestion.RagTokenizer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.*;
import org.springframework.ai.openai.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Version-scoped official Spring AI clients and bounded rerank transport. @author czhcc */
@Component
@RequiredArgsConstructor
public class ModelGateway {
  private final SecretVault vault;
  private final EndpointPolicy policy;
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient client = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  /** Creates an official Spring AI text client for one immutable version. @author czhcc */
  public OpenAiChatModel chatModel(ModelVersion version) {
    if (!version.getKind().equals("CHAT")) throw new IllegalArgumentException();
    var sdk = sdkClient(version);
    return OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(sdk.async())
        .options(OpenAiChatOptions.builder().baseUrl(version.getBaseUrl()).apiKey("")
            .model(version.getModel()).maxTokens(version.getMaxOutputTokens()).temperature(0.0)
            .timeout(Duration.ofSeconds(version.getTimeoutSeconds())).maxRetries(0).build())
        .build();
  }

  /** Uses the official client's streaming implementation. @author czhcc */
  public OpenAiChatModel streamingChatModel(ModelVersion version) { return chatModel(version); }

  /** Creates an official Spring AI embedding client for the captured vector space. @author czhcc */
  public OpenAiEmbeddingModel embeddingModel(ModelVersion version) {
    if (!version.getKind().equals("EMBEDDING")) throw new IllegalArgumentException();
    return OpenAiEmbeddingModel.builder().openAiClient(sdkClient(version)).metadataMode(MetadataMode.NONE)
        .options(OpenAiEmbeddingOptions.builder().baseUrl(version.getBaseUrl()).apiKey("")
            .model(version.getModel()).encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
            .timeout(Duration.ofSeconds(version.getTimeoutSeconds())).maxRetries(0).build())
        .build();
  }

  private OpenAIClient sdkClient(ModelVersion version) {
    policy.validate(version.getBaseUrl());
    String key = vault.decrypt(version.getCipher(), version.getProfileId());
    var options = ClientOptions.builder().baseUrl(version.getBaseUrl())
        .apiKey(key.isEmpty() ? "unused-no-auth" : key)
        .httpClient(new SpringAiTransport(version.getTimeoutSeconds(), key.isEmpty()))
        .timeout(Duration.ofSeconds(version.getTimeoutSeconds())).maxRetries(0).logLevel(LogLevel.OFF)
        .build();
    return new OpenAIClientImpl(options);
  }

  /** Calls the configured text model and verifies a complete, nonempty answer. @author czhcc */
  public String chat(ModelVersion version, List<Map<String, String>> messages) {
    Prompt prompt = prompt(version, messages);
    return guarded(() -> {
      var response = chatModel(version).call(prompt);
      if (response == null || response.getResults().size() != 1)
        throw new ModelFailure("model_truncated_or_invalid");
      var generation = response.getResult();
      if (!"stop".equalsIgnoreCase(generation.getMetadata().getFinishReason()))
        throw new ModelFailure("model_truncated_or_invalid");
      String text = generation.getOutput().getText();
      if (text == null || text.isBlank() || text.length() > 2000000)
        throw new ModelFailure("model_empty_or_oversize");
      return text;
    });
  }

  private Prompt prompt(ModelVersion version, List<Map<String, String>> messages) {
    if (!version.getKind().equals("CHAT")) throw new IllegalArgumentException();
    int tokens = messages.stream().mapToInt(m -> tokenizer.encode(m.get("content")).length + 8).sum() + 16;
    if (tokens + version.getMaxOutputTokens() > version.getContextTokens())
      throw new ModelFailure("model_context_exceeded");
    List<Message> list = messages.stream().<Message>map(m -> switch (m.get("role")) {
      case "system" -> new SystemMessage(m.get("content"));
      case "assistant" -> new AssistantMessage(m.get("content"));
      default -> new UserMessage(m.get("content"));
    }).toList();
    return new Prompt(list);
  }

  /** Propagates provider deltas and rejects incomplete, failed or cancelled streams. @author czhcc */
  public void stream(ModelVersion version, List<Map<String, String>> messages,
      java.util.function.Consumer<String> delta) {
    Prompt prompt = prompt(version, messages);
    guarded(() -> {
      var stopped = new java.util.concurrent.atomic.AtomicBoolean();
      var content = new java.util.concurrent.atomic.AtomicBoolean();
      streamingChatModel(version).stream(prompt)
          .doOnNext(response -> {
            if (response.getResults().isEmpty()) return;
            if (response.getResults().size() != 1) throw new ModelFailure("model_stream_invalid");
            var generation = response.getResult();
            String finish = generation.getMetadata().getFinishReason();
            String text = generation.getOutput().getText();
            if (finish != null && !finish.isBlank() && !"stop".equalsIgnoreCase(finish))
              throw new ModelFailure("model_truncated_or_invalid");
            if (text != null && !text.isEmpty()) {
              if (stopped.get()) throw new ModelFailure("model_stream_invalid");
              content.set(true);
              delta.accept(text);
            }
            if ("stop".equalsIgnoreCase(finish)) stopped.set(true);
          })
          .concatWith(Flux.defer(() -> stopped.get() && content.get() ? Flux.empty()
              : Flux.error(new ModelFailure("model_stream_incomplete"))))
          .timeout(Duration.ofSeconds(version.getTimeoutSeconds()), Flux.error(new ModelFailure("model_timeout")))
          .blockLast();
      return null;
    });
  }

  /** Generates one validated vector using the saved embedding version. @author czhcc */
  public float[] embed(ModelVersion version, String text) { return embedBatch(version, List.of(text)).getFirst(); }

  /** Batches query vectors without changing the generation's embedding configuration. @author czhcc */
  public List<float[]> embedBatch(ModelVersion version, List<String> texts) {
    if (!version.getKind().equals("EMBEDDING") || texts.isEmpty() || texts.size() > 16)
      throw new IllegalArgumentException();
    for (String text : texts)
      if (tokenizer.encode(text).length > version.getContextTokens())
        throw new ModelFailure("embedding_context_exceeded");
    return guarded(() -> {
      var response = embeddingModel(version).call(new EmbeddingRequest(texts, null));
      if (response.getResults().size() != texts.size()) throw new ModelFailure("embedding_invalid");
      var ordered = new float[texts.size()][];
      for (var row : response.getResults()) {
        int index = row.getIndex();
        float[] vector = row.getOutput();
        if (index < 0 || index >= texts.size() || ordered[index] != null
            || vector.length != version.getDimensions()) throw new ModelFailure("embedding_dimension_mismatch");
        double norm = 0;
        for (float value : vector) {
          if (!Float.isFinite(value)) throw new ModelFailure("embedding_invalid");
          norm += (double) value * value;
        }
        if (norm == 0) throw new ModelFailure("embedding_invalid");
        ordered[index] = vector;
      }
      return Arrays.asList(ordered);
    });
  }

  private <T> T guarded(Supplier<T> action) {
    try {
      return action.get();
    } catch (RuntimeException error) {
      for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof ModelFailure failure) throw failure;
        if (cause instanceof java.io.InterruptedIOException || cause instanceof java.util.concurrent.TimeoutException)
          throw new ModelFailure("model_timeout");
      }
      throw new ModelFailure(Thread.currentThread().isInterrupted() ? "model_interrupted" : "model_unavailable");
    }
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
