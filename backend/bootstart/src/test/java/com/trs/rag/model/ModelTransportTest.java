package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Credential authentication, redirect prevention and bounded transport tests. @author agent */
class ModelTransportTest {
  @Test void nativeToolProbeRequiresARealToolCall() throws Exception {
    var supported = new java.util.concurrent.atomic.AtomicBoolean(true);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(body.contains("connection_probe"));
      assertEquals("required", tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("tool_choice").asText());
      String response = supported.get()
          ? "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"p\",\"type\":\"function\",\"function\":{\"name\":\"connection_probe\",\"arguments\":\"{}\"}}]}}]}"
          : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";
      byte[] bytes = OpenAiFixtures.json(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }); server.start();
    try {
      var model = new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "CHAT", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "probe", "", 0, 16000, 100, 3);
      var gateway = new ModelGateway(new SecretVault(""), new EndpointPolicy(), mock(RagTokenizer.class));
      assertTrue(gateway.supportsTools(model)); supported.set(false); assertFalse(gateway.supportsTools(model));
    } finally { server.stop(0); }
  }
  @Test void nativeDecisionsPreserveRequiredAndAutoToolChoice() throws Exception {
    var choices = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      var body = mapper.readTree(exchange.getRequestBody().readAllBytes()); choices.add(body.path("tool_choice").asText());
      assertFalse(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
      byte[] bytes = OpenAiFixtures.json("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }); server.start();
    try {
      var model = new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "CHAT", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "native", "", 0, 16000, 100, 3, Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
      var gateway = new ModelGateway(new SecretVault(""), new EndpointPolicy(), mock(RagTokenizer.class));
      for (String choice : List.of("required", "auto")) gateway.decide(model, new org.springframework.ai.chat.prompt.Prompt("question", org.springframework.ai.openai.OpenAiChatOptions.builder().toolChoice(choice).build()));
      assertEquals(List.of("required", "auto"), choices);
    } finally { server.stop(0); }
  }
  @Test
  void textAndEmbeddingFactoriesUseOfficialSpringAiImplementations() {
    var gateway = new ModelGateway(new SecretVault(""), new EndpointPolicy(), mock(RagTokenizer.class));
    var chat = new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "CHAT",
        "http://127.0.0.1:6116/v1", "configured-chat", "", 0, 16000, 100, 2);
    var embedding = new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "EMBEDDING",
        "http://127.0.0.1:6116/v1", "configured-embedding", "", 3, 16000, 100, 2);
    assertInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class, gateway.chatModel(chat));
    assertInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class, gateway.streamingChatModel(chat));
    assertInstanceOf(org.springframework.ai.openai.OpenAiEmbeddingModel.class, gateway.embeddingModel(embedding));
  }

  /** Verifies compatibility with providers that reject cleartext HTTP/2 upgrades. @author czhcc */
  @ParameterizedTest
  @ValueSource(strings = {"CHAT", "EMBEDDING", "RERANK", "STREAM", "JSON_STREAM"})
  void modelCallsUseHttp11WithoutUpgrade(String kind) throws Exception {
    var upgrade = new java.util.concurrent.atomic.AtomicReference<String>();
    var protocol = new java.util.concurrent.atomic.AtomicReference<String>();
    var requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
    var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          try {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            protocol.set(exchange.getProtocol());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (upgrade.get() != null) {
              exchange.sendResponseHeaders(400, -1);
              return;
            }
            String response =
                switch (kind) {
                  case "EMBEDDING" -> "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}";
                  case "RERANK" -> "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
                  case "STREAM", "JSON_STREAM" ->
                      "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}\n\n"
                          + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                          + "data: [DONE]\n\n";
                  default ->
                      "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
                };
            exchange.getResponseHeaders().set(
                "Content-Type", kind.endsWith("STREAM") ? "text/event-stream" : "application/json");
            byte[] bytes = (kind.endsWith("STREAM") ? OpenAiFixtures.events(response) : OpenAiFixtures.json(response))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var tokenizer = mock(RagTokenizer.class);
      when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
      var gateway = new ModelGateway(new SecretVault(""), new EndpointPolicy(), tokenizer);
      var extras = new LinkedHashMap<String, Object>();
      if (Set.of("CHAT", "STREAM", "JSON_STREAM").contains(kind)) {
        extras.put("chat_template_kwargs", Map.of("enable_thinking", false));
        extras.put("custom_null", null);
      }
      var version = new ModelVersion(
          UUID.randomUUID(), UUID.randomUUID(), kind.endsWith("STREAM") ? "CHAT" : kind,
          "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test", "",
          kind.equals("EMBEDDING") ? 3 : 0, 16000, 100, 3,
          extras);
      switch (kind) {
        case "EMBEDDING" -> assertArrayEquals(new float[] {1, 2, 3}, gateway.embed(version, "test"));
        case "RERANK" -> assertEquals(1, gateway.rerank(version, "test", List.of("test")).size());
        case "STREAM", "JSON_STREAM" -> {
          var deltas = new ArrayList<String>();
          var messages = List.of(Map.of("role", "user", "content", "Return JSON"));
          if (kind.equals("JSON_STREAM")) gateway.streamJson(version, messages, deltas::add, () -> {});
          else gateway.stream(version, messages, deltas::add);
          assertEquals("OK", String.join("", deltas));
        }
        default -> assertEquals("OK", gateway.chat(version, List.of(Map.of("role", "user", "content", "test"))));
      }
      assertEquals("HTTP/1.1", protocol.get());
      assertNull(upgrade.get());
      assertNull(authorization.get());
      assertEquals("test", tools.jackson.databind.json.JsonMapper.builder().build()
          .readTree(requestBody.get()).path("model").asText());
      if (Set.of("CHAT", "STREAM", "JSON_STREAM").contains(kind)) {
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody.get());
        if (kind.equals("JSON_STREAM")) assertEquals("json_object", body.path("response_format").path("type").asText());
        else assertFalse(body.has("response_format"));
        assertFalse(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
        assertFalse(body.has("extra_body"));
        assertTrue(body.has("custom_null") && body.path("custom_null").isNull());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void unsupportedJsonModeFailsWithoutUnconstrainedRetry() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try {
        exchange.getRequestBody().readAllBytes();
        calls.incrementAndGet();
        exchange.sendResponseHeaders(400, -1);
      } finally { exchange.close(); }
    });
    server.start();
    try {
      var tokenizer = mock(RagTokenizer.class);
      when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
      var gateway = new ModelGateway(new SecretVault(""), new EndpointPolicy(), tokenizer);
      var version = new ModelVersion(UUID.randomUUID(), UUID.randomUUID(), "CHAT",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test", "", 0, 16000, 100, 3);
      var deltas = new ArrayList<String>();
      assertThrows(ModelFailure.class, () -> gateway.streamJson(version,
          List.of(Map.of("role", "user", "content", "Return JSON")), deltas::add, () -> {}));
      assertEquals(1, calls.get());
      assertTrue(deltas.isEmpty());
    } finally { server.stop(0); }
  }

  @Test
  void embeddingBatchPreservesOrderAndRejectsInvalidVectors() throws Exception {
    var payload = new java.util.concurrent.atomic.AtomicReference<String>();
    var auth = new java.util.concurrent.atomic.AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/embeddings", exchange -> {
      try {
        exchange.getRequestBody().readAllBytes();
        auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = OpenAiFixtures.json(payload.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally { exchange.close(); }
    });
    server.start();
    try {
      var tokenizer = mock(RagTokenizer.class);
      when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
      var vault = new SecretVault("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
      UUID profile = UUID.randomUUID();
      var version = new ModelVersion(UUID.randomUUID(), profile, "EMBEDDING",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "saved-vector-model",
          vault.encrypt("saved-key", profile), 3, 16000, 100, 2);
      var gateway = new ModelGateway(vault, new EndpointPolicy(), tokenizer);
      payload.set("{\"data\":[{\"index\":1,\"embedding\":[4,5,6]},{\"index\":0,\"embedding\":[1,2,3]}]}");
      var vectors = gateway.embedBatch(version, List.of("first", "second"));
      assertArrayEquals(new float[] {1,2,3}, vectors.get(0));
      assertArrayEquals(new float[] {4,5,6}, vectors.get(1));
      assertEquals("Bearer saved-key", auth.get());
      for (String rows : List.of(
          "{\"index\":0,\"embedding\":[0,0,0]}",
          "{\"index\":0,\"embedding\":[1,2]}",
          "{\"index\":1,\"embedding\":[1,2,3]}",
          "{\"index\":0,\"embedding\":[1,2,3]},{\"index\":0,\"embedding\":[4,5,6]}")) {
        payload.set("{\"data\":[" + rows + "]}");
        assertThrows(ModelFailure.class, () -> gateway.embed(version, "first"));
      }
    } finally { server.stop(0); }
  }

  @Test
  void credentialsAreAuthenticatedBoundToProfileAndRequireMasterKey() {
    UUID profile = UUID.randomUUID();
    var vault = new SecretVault("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    String a = vault.encrypt("secret", profile), b = vault.encrypt("secret", profile);
    assertNotEquals(a, b);
    assertEquals("secret", vault.decrypt(a, profile));
    assertThrows(ModelFailure.class, () -> vault.decrypt(a, UUID.randomUUID()));
    assertThrows(ModelFailure.class, () -> new SecretVault("").encrypt("secret", profile));
    assertThrows(
        ModelFailure.class,
        () -> new SecretVault("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=").decrypt(a, profile));
  }

  @Test
  void configuredHttpAndHttpsEndpointsNeedNoEnvironmentOverrides() {
    var policy = new EndpointPolicy();
    assertEquals(
        URI.create("http://192.168.5.68:6116/v1"),
        policy.validate("http://192.168.5.68:6116/v1"));
    assertEquals("api.example.com", policy.validate("https://api.example.com/v1").getHost());
    assertEquals("other.example.com", policy.validate("https://other.example.com/v1").getHost());
  }

  @Test
  void unsafeSchemesCredentialsFragmentsAndInvalidPortsAreRejected() {
    var policy = new EndpointPolicy();
    for (String u :
        List.of(
            "file:///tmp/model",
            "ftp://api.example.com/v1",
            "/v1",
            "http://api.example.com:0/v1",
            "http://api.example.com:65536/v1",
            "http://api.example.com/v1/../private",
            "https://user:pass@api.example.com/v1",
            "https://api.example.com/v1#secret",
            "https://api.example.com/v1?secret=x"))
      assertThrows(IllegalArgumentException.class, () -> policy.validate(u));
    assertThrows(IllegalArgumentException.class, () -> policy.validate(null));
  }

  @Test
  void responseBodyTimeoutIsEnforcedAfterHeaders() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/v1/chat/completions",
        e -> {
          try {
            e.sendResponseHeaders(200, 100);
            e.getResponseBody().flush();
            Thread.sleep(3000);
          } catch (Exception ignored) {
          } finally {
            e.close();
          }
        });
    server.start();
    try {
      var tokenizer = mock(RagTokenizer.class);
      when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
      var gateway =
          new ModelGateway(new SecretVault(""), new EndpointPolicy(), tokenizer);
      var model =
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
              1);
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () ->
              assertThrows(
                  ModelFailure.class,
                  () -> gateway.chat(model, List.of(Map.of("role", "user", "content", "test")))));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rerankProtocolRejectsCoercedAndOverflowingIndicesAndInvalidScores() throws Exception {
    var payload = new java.util.concurrent.atomic.AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/rerank",
        e -> {
          try {
            e.getRequestBody().readAllBytes();
            byte[] bytes = payload.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
          } finally {
            e.close();
          }
        });
    server.start();
    try {
      var gateway =
          new ModelGateway(
              new SecretVault(""), new EndpointPolicy(), mock(RagTokenizer.class));
      var version =
          new ModelVersion(
              UUID.randomUUID(),
              UUID.randomUUID(),
              "RERANK",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "test",
              "",
              0,
              16000,
              100,
              2);
      for (String index : List.of("0.5", "\"0\"", "4294967296", "true", "-1", "2")) {
        payload.set("{\"results\":[{\"index\":" + index + ",\"relevance_score\":0.9}]}");
        assertThrows(
            ModelFailure.class, () -> gateway.rerank(version, "q", List.of("document")), index);
      }
      for (String score : List.of("\"0.9\"", "true", "1e999", "null")) {
        payload.set("{\"results\":[{\"index\":0,\"relevance_score\":" + score + "}]}");
        assertThrows(
            ModelFailure.class, () -> gateway.rerank(version, "q", List.of("document")), score);
      }
    } finally {
      server.stop(0);
    }
  }
}
