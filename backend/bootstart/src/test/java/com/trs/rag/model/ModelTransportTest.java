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

/** Credential authentication, redirect prevention and bounded transport tests. @author agent */
class ModelTransportTest {
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
  void missingAllowlistUnsafeSchemesCredentialsAndFragmentsAreRejected() {
    var policy = new EndpointPolicy("api.example.com", false);
    for (String u :
        List.of(
            "http://api.example.com/v1",
            "https://other.example.com/v1",
            "https://user:pass@api.example.com/v1",
            "https://api.example.com/v1#secret",
            "https://api.example.com/v1?secret=x"))
      assertThrows(IllegalArgumentException.class, () -> policy.validate(u));
    assertEquals("api.example.com", policy.validate("https://api.example.com/v1").getHost());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EndpointPolicy("", false).validate("https://api.example.com/v1"));
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
          new ModelGateway(new SecretVault(""), new EndpointPolicy("127.0.0.1", true), tokenizer);
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
              new SecretVault(""), new EndpointPolicy("127.0.0.1", true), mock(RagTokenizer.class));
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
