package com.trs.app.rag.model;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;

/** Bounded OpenAI-compatible SSE transport backing Spring AI StreamingChatModel. @author agent */
final class ModelStream {
  private ModelStream() {}

  static Flux<ChatResponse> open(
      ModelVersion v, Prompt prompt, SecretVault vault, EndpointPolicy policy, HttpClient client) {
    return Flux.create(
        sink -> {
          var input = new AtomicReference<InputStream>();
          var future = new AtomicReference<java.util.concurrent.CompletableFuture<?>>();
          var worker = new AtomicReference<Thread>();
          var timer =
              Mono.delay(Duration.ofSeconds(v.getTimeoutSeconds()))
                  .subscribe(t -> sink.error(new ModelFailure("model_timeout")));
          sink.onDispose(
              () -> {
                timer.dispose();
                var f = future.get();
                if (f != null) f.cancel(true);
                var thread = worker.get();
                if (thread != null && thread != Thread.currentThread()) thread.interrupt();
                var stream = input.get();
                if (stream != null)
                  try {
                    stream.close();
                  } catch (IOException ignored) {
                  }
              });
          Thread thread =
              Thread.ofVirtual()
                  .unstarted(
                      () -> {
                        try {
                          policy.validate(v.getBaseUrl());
                          var json = JsonMapper.builder().build();
                          var messages =
                              prompt.getInstructions().stream()
                                  .map(
                                      m ->
                                          Map.of(
                                              "role",
                                              m.getMessageType().getValue(),
                                              "content",
                                              m.getText()))
                                  .toList();
                          var builder =
                              HttpRequest.newBuilder(
                                      URI.create(v.getBaseUrl() + "/chat/completions"))
                                  .timeout(Duration.ofSeconds(v.getTimeoutSeconds()))
                                  .header("Content-Type", "application/json")
                                  .header("Accept", "text/event-stream");
                          String key = vault.decrypt(v.getCipher(), v.getProfileId());
                          if (!key.isEmpty()) builder.header("Authorization", "Bearer " + key);
                          var sent =
                              client.sendAsync(
                                  builder
                                      .POST(
                                          HttpRequest.BodyPublishers.ofString(
                                              json.writeValueAsString(
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
                                                      true))))
                                      .build(),
                                  HttpResponse.BodyHandlers.ofInputStream());
                          future.set(sent);
                          if (sink.isCancelled()) {
                            sent.cancel(true);
                            return;
                          }
                          var response = sent.get();
                          input.set(response.body());
                          if (sink.isCancelled()) {
                            response.body().close();
                            return;
                          }
                          try (var stream = response.body()) {
                            if (response.statusCode() < 200 || response.statusCode() > 299)
                              throw new ModelFailure(
                                  response.statusCode() == 429
                                      ? "model_rate_limited"
                                      : "model_http_error");
                            if (!response
                                .headers()
                                .firstValue("Content-Type")
                                .orElse("")
                                .toLowerCase(Locale.ROOT)
                                .startsWith("text/event-stream"))
                              throw new ModelFailure("model_stream_invalid");
                            var reader =
                                new InputStreamReader(
                                    new LimitedInput(stream),
                                    StandardCharsets.UTF_8
                                        .newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT));
                            StringBuilder line = new StringBuilder(), data = new StringBuilder();
                            boolean stopped = false, content = false;
                            int value;
                            while (!sink.isCancelled() && (value = reader.read()) != -1) {
                              if (value == '\r') continue;
                              if (value != '\n') {
                                line.append((char) value);
                                if (line.length() > 65536)
                                  throw new ModelFailure("model_response_oversize");
                                continue;
                              }
                              String row = line.toString();
                              line.setLength(0);
                              if (row.startsWith("data:")) {
                                if (!data.isEmpty()) data.append('\n');
                                data.append(row.substring(5).stripLeading());
                                if (data.length() > 65536)
                                  throw new ModelFailure("model_response_oversize");
                              } else if (row.isEmpty() && !data.isEmpty()) {
                                String event = data.toString();
                                data.setLength(0);
                                if (event.equals("[DONE]")) {
                                  if (!stopped || !content)
                                    throw new ModelFailure("model_stream_incomplete");
                                  sink.complete();
                                  return;
                                }
                                var choices = json.readTree(event).path("choices");
                                if (choices.size() != 1)
                                  throw new ModelFailure("model_stream_invalid");
                                var choice = choices.get(0);
                                String finish = choice.path("finish_reason").asText("");
                                if (!finish.isEmpty() && !finish.equals("stop"))
                                  throw new ModelFailure("model_truncated_or_invalid");
                                String text = choice.path("delta").path("content").asText("");
                                if (stopped && !text.isEmpty())
                                  throw new ModelFailure("model_stream_invalid");
                                if (!text.isEmpty()) {
                                  content = true;
                                  sink.next(
                                      new ChatResponse(
                                          List.of(new Generation(new AssistantMessage(text)))));
                                }
                                if (finish.equals("stop")) stopped = true;
                              }
                            }
                            if (!sink.isCancelled())
                              throw new ModelFailure("model_stream_incomplete");
                          }
                        } catch (Exception error) {
                          if (!sink.isCancelled())
                            sink.error(
                                error instanceof ModelFailure
                                    ? error
                                    : new ModelFailure("model_stream_unavailable"));
                        }
                      });
          worker.set(thread);
          if (!sink.isCancelled()) thread.start();
        });
  }

  /** Enforces a total four MiB wire ceiling, including unrecognized SSE fields. @author agent */
  private static final class LimitedInput extends FilterInputStream {
    private int count;

    LimitedInput(InputStream stream) {
      super(stream);
    }

    /** Counts single-byte reads. @author agent */
    @Override
    public int read() throws IOException {
      int v = super.read();
      if (v >= 0) check(1);
      return v;
    }

    /** Counts bulk reads before decoding Unicode. @author agent */
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int n = in.read(bytes, offset, length);
      if (n > 0) check(n);
      return n;
    }

    private void check(int n) {
      count += n;
      if (count > 4 * 1024 * 1024) throw new ModelFailure("model_response_oversize");
    }
  }
}
