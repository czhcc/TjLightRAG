package com.trs.app.rag.model;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.io.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import okhttp3.*;
import okio.BufferedSink;

/** Transport safeguards for official Spring AI models; SDKs own model serialization. @author czhcc */
final class SpringAiTransport implements com.openai.core.http.HttpClient {
  private static final OkHttpClient SHARED = new OkHttpClient.Builder()
      .protocols(List.of(Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false)
      .retryOnConnectionFailure(false).build();
  private final OkHttpClient client;
  private final boolean noAuth;

  SpringAiTransport(int seconds, boolean noAuth) {
    this.client = SHARED.newBuilder().callTimeout(Duration.ofSeconds(seconds))
        .connectTimeout(Duration.ofSeconds(Math.min(10, seconds)))
        .readTimeout(Duration.ofSeconds(seconds)).build();
    this.noAuth = noAuth;
  }

  private Call call(HttpRequest request) {
    var builder = new Request.Builder().url(request.url());
    for (String name : request.headers().names())
      for (String value : request.headers().values(name))
        if (!noAuth || !name.equalsIgnoreCase("Authorization")) builder.addHeader(name, value);
    var body = request.body();
    RequestBody output = body == null ? null : new RequestBody() {
      /** Uses the SDK's content type. @author czhcc */
      @Override
      public MediaType contentType() { return MediaType.parse(body.contentType()); }
      /** Uses the SDK's serialized length. @author czhcc */
      @Override
      public long contentLength() { return body.contentLength(); }
      /** Delegates serialization to the SDK. @author czhcc */
      @Override
      public void writeTo(BufferedSink sink) { body.writeTo(sink.outputStream()); }
    };
    return client.newCall(builder.method(request.method().name(), output).build());
  }

  /** Executes an SDK request without exposing provider errors. @author czhcc */
  @Override
  public HttpResponse execute(HttpRequest request, RequestOptions options) {
    try {
      return response(call(request).execute());
    } catch (IOException error) {
      throw failure(error);
    } finally {
      if (request.body() != null) request.body().close();
    }
  }

  /** Connects SDK cancellation to the active HTTP call. @author czhcc */
  @Override
  public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
    var result = new CompletableFuture<HttpResponse>();
    var call = call(request);
    result.whenComplete((response, error) -> {
      if (result.isCancelled()) call.cancel();
      if (request.body() != null) request.body().close();
    });
    call.enqueue(new Callback() {
      /** Sanitizes transport failures. @author czhcc */
      @Override
      public void onFailure(Call active, IOException error) { result.completeExceptionally(failure(error)); }
      /** Transfers ownership of a bounded response to the SDK. @author czhcc */
      @Override
      public void onResponse(Call active, Response response) {
        try {
          var wrapped = response(response);
          if (!result.complete(wrapped)) wrapped.close();
        } catch (RuntimeException error) {
          response.close();
          result.completeExceptionally(error);
        }
      }
    });
    return result;
  }

  private HttpResponse response(Response response) {
    if (!response.isSuccessful()) {
      int status = response.code();
      response.close();
      throw new ModelFailure(status == 429 ? "model_rate_limited" : "model_http_error");
    }
    boolean stream = response.request().url().encodedPath().endsWith("/chat/completions")
        && response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream");
    var input = new WireInput(response.body().byteStream(), stream);
    return new HttpResponse() {
      /** Returns the provider HTTP status. @author czhcc */
      @Override
      public int statusCode() { return response.code(); }
      /** Exposes response headers to the SDK. @author czhcc */
      @Override
      public Headers headers() { return Headers.builder().putAll(response.headers().toMultimap()).build(); }
      /** Supplies the bounded provider stream. @author czhcc */
      @Override
      public InputStream body() { return input; }
      /** Releases the HTTP response on completion or cancellation. @author czhcc */
      @Override
      public void close() { response.close(); }
    };
  }

  private static ModelFailure failure(IOException error) {
    return new ModelFailure(error instanceof java.io.InterruptedIOException ? "model_timeout" : "model_unavailable");
  }

  /** Leaves shared connection and dispatcher lifecycle to the application. @author czhcc */
  @Override
  public void close() {}

  /** Limits wire bytes and requires the provider's SSE completion marker. @author czhcc */
  private static final class WireInput extends FilterInputStream {
    private final boolean stream;
    private final StringBuilder line = new StringBuilder();
    private int count;
    private boolean done;
    WireInput(InputStream input, boolean stream) { super(input); this.stream = stream; }
    /** Counts and validates single-byte reads. @author czhcc */
    @Override
    public int read() throws IOException {
      int value = in.read();
      check(value);
      return value;
    }
    /** Counts and validates buffered reads. @author czhcc */
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int size = in.read(bytes, offset, length);
      if (size < 0) check(-1);
      else for (int i = offset; i < offset + size; i++) check(bytes[i] & 255);
      return size;
    }
    private void check(int value) {
      if (value < 0) {
        if (stream && !done) throw new ModelFailure("model_stream_incomplete");
        return;
      }
      if (++count > 4 * 1024 * 1024) throw new ModelFailure("model_response_oversize");
      if (!stream) return;
      if (value == '\n') {
        if (line.toString().matches("data:\\s*\\[DONE\\]\\s*")) done = true;
        line.setLength(0);
      } else if (value != '\r') {
        line.append((char) value);
        if (line.length() > 65536) throw new ModelFailure("model_response_oversize");
      }
    }
  }
}
