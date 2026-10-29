package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Real provider HTTP streams, not buffered-answer simulations. @author agent */
class ModelStreamingTest {
  @Test
  void finishReasonAndProviderDoneAreBothRequired() throws Exception {
    var payload = new java.util.concurrent.atomic.AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        byte[] bytes = OpenAiFixtures.events(payload.get()).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally { exchange.close(); }
    });
    server.start();
    try {
      var tokenizer = mock(RagTokenizer.class);
      when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
      var gateway = new ModelGateway(new SecretVault(""), new EndpointPolicy(), tokenizer);
      var version = new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "CHAT",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "saved-chat", "", 0, 16000, 100, 3);
      String first = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n";
      for (String ending : List.of(
          "data: [DONE]\n\n",
          "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
          "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n")) {
        payload.set(first + ending);
        assertThrows(ModelFailure.class, () -> gateway.stream(version,
            List.of(Map.of("role", "user", "content", "question")), ignored -> {}));
      }
    } finally { server.stop(0); }
  }

  @Test
  void unicodeDeltasArriveBeforeProviderCompletesAndTruncatedStreamsFail() throws Exception {
    for (boolean truncated : new boolean[] {false, true}) {
      var first = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext(
          "/v1/chat/completions",
          e -> {
            try {
              String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              assertTrue(body.contains("\"stream\":true"));
              e.getResponseHeaders().set("Content-Type", "text/event-stream");
              e.sendResponseHeaders(200, 0);
              e.getResponseBody()
                  .write(
                      OpenAiFixtures.events("data: {\"choices\":[{\"delta\":{\"content\":\"中文🙂\"},\"finish_reason\":null}]}\n\n")
                          .getBytes(StandardCharsets.UTF_8));
              e.getResponseBody().flush();
              release.await(3, TimeUnit.SECONDS);
              if (!truncated)
                e.getResponseBody()
                    .write(
                        OpenAiFixtures.events("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                            .getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            } finally {
              e.close();
            }
          });
      server.start();
      try {
        var t = mock(RagTokenizer.class);
        when(t.encode(anyString())).thenReturn(new int[] {1});
        var gateway =
            new ModelGateway(new SecretVault(""), new EndpointPolicy(), t);
        var version =
            new ModelVersion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CHAT",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test",
                "",
                0,
                16000,
                100,
                2);
        var texts = new CopyOnWriteArrayList<String>();
        var future =
            executor.submit(
                () ->
                    gateway.stream(
                        version,
                        List.of(Map.of("role", "user", "content", "q")),
                        delta -> {
                          texts.add(delta);
                          first.countDown();
                        }));
        assertTrue(first.await(2, TimeUnit.SECONDS));
        assertFalse(future.isDone());
        assertEquals(List.of("中文🙂"), texts);
        release.countDown();
        if (truncated)
          assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        else future.get(3, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
      }
    }
  }

  @Test
  void idleBodyIsTimedOutAndCancellationClosesTransport() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    var entered = new CountDownLatch(1);
    server.createContext(
        "/v1/chat/completions",
        e -> {
          try {
            e.getRequestBody().readAllBytes();
            e.getResponseHeaders().set("Content-Type", "text/event-stream");
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().flush();
            entered.countDown();
            Thread.sleep(5000);
          } catch (Exception ignored) {
          } finally {
            e.close();
          }
        });
    server.start();
    try {
      var t = mock(RagTokenizer.class);
      when(t.encode(anyString())).thenReturn(new int[] {1});
      var g = new ModelGateway(new SecretVault(""), new EndpointPolicy(), t);
      var v =
          new ModelVersion(
              UUID.randomUUID(),
              UUID.randomUUID(),
              "CHAT",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "t",
              "",
              0,
              16000,
              100,
              1);
      assertTimeoutPreemptively(
          Duration.ofSeconds(3),
          () ->
              assertThrows(
                  ModelFailure.class,
                  () -> g.stream(v, List.of(Map.of("role", "user", "content", "q")), s -> {})));
      var task =
          executor.submit(
              () -> g.stream(v, List.of(Map.of("role", "user", "content", "q")), s -> {}));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      task.cancel(true);
      assertTrue(task.isCancelled());
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
