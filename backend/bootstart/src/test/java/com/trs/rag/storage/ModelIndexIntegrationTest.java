package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.trs.app.rag.*;
import com.trs.app.rag.index.*;
import com.trs.app.rag.ingestion.*;
import com.trs.app.rag.model.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

/** Real database and wire-protocol model/version/index contracts. @author agent */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "rag.ingestion.enabled=false",
      "rag.indexing.enabled=false",
      "rag.models.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "rag.models.allowed-hosts=127.0.0.1",
      "rag.models.allow-http=true"
    })
class ModelIndexIntegrationTest {
  @Container
  static final GenericContainer<?> DB =
      new GenericContainer<>(VectorStorageIntegrationTest.IMAGE)
          .withEnv("POSTGRES_DB", "models")
          .withEnv("POSTGRES_USER", "models")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String url = "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/models";
    for (String k : List.of("spring.datasource.url", "spring.flyway.url")) r.add(k, () -> url);
    for (String k : List.of("spring.datasource.username", "spring.flyway.user"))
      r.add(k, () -> "models");
    for (String k : List.of("spring.datasource.password", "spring.flyway.password"))
      r.add(k, () -> "test-only");
    r.add("rag.vector.dimensions", () -> "3");
    r.add("rag.vector.model", () -> "fixture-3d");
  }

  @Autowired ModelConfigurationService models;
  @Autowired IndexService indexes;
  @Autowired IndexProcessor indexer;
  @Autowired KnowledgeBaseService bases;
  @Autowired DocumentService documents;
  @Autowired JobService jobs;
  @Autowired IngestionProcessor ingestion;
  @Autowired JdbcTemplate jdbc;
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  HttpServer server;
  UUID kb;
  AtomicInteger embeddingCalls = new AtomicInteger();
  AtomicBoolean failEmbedding = new AtomicBoolean();
  AtomicInteger failAt = new AtomicInteger(-1);
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setup() throws Exception {
    jdbc.update("DELETE FROM rag_model_binding");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        ex -> {
          try {
            var body =
                json.readTree(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = ex.getRequestURI().getPath();
            String response;
            assertEquals(
                "Bearer test-private-key", ex.getRequestHeaders().getFirst("Authorization"));
            if (path.endsWith("embeddings")) {
              int call = embeddingCalls.incrementAndGet();
              if (failEmbedding.get() || call == failAt.get()) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
              }
              int d = body.path("model").asText().endsWith("4") ? 4 : 3;
              List<Map<String, Object>> data = new ArrayList<>();
              var input = body.path("input");
              int count = input.isArray() ? input.size() : 1;
              for (int i = 0; i < count; i++)
                data.add(
                    Map.of(
                        "index",
                        i,
                        "object",
                        "embedding",
                        "embedding",
                        d == 4 ? List.of(1.0, .2, .3, .4) : List.of(1.0, .2, .3)));
              response =
                  json.writeValueAsString(
                      Map.of(
                          "object",
                          "list",
                          "data",
                          data,
                          "model",
                          body.path("model").asText(),
                          "usage",
                          Map.of("prompt_tokens", 1, "total_tokens", 1)));
            } else if (path.endsWith("rerank"))
              response = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
            else {
              String answer =
                  "entity<|#|>Alice<|#|>person<|#|>Researcher\n"
                      + "entity<|#|>Bob<|#|>person<|#|>Scientist\n"
                      + "relation<|#|>Alice<|#|>Bob<|#|>research<|#|>Collaborate\n"
                      + "<|COMPLETE|>";
              response =
                  json.writeValueAsString(
                      Map.of(
                          "id",
                          "fixture",
                          "object",
                          "chat.completion",
                          "created",
                          1,
                          "model",
                          "chat",
                          "choices",
                          List.of(
                              Map.of(
                                  "index",
                                  0,
                                  "message",
                                  Map.of("role", "assistant", "content", answer),
                                  "finish_reason",
                                  "stop")),
                          "usage",
                          Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
          } catch (Throwable failure) {
            ex.close();
          }
        });
    server.start();
    kb = bases.create("admin", "Stage 3", "").getId();
  }

  @AfterEach
  void cleanup() {
    if (kb != null) bases.delete(kb, "admin");
    if (server != null) server.stop(0);
  }

  ModelRequest request(String kind, String model, int dims) {
    var r = new ModelRequest();
    r.setName("model-" + UUID.randomUUID());
    r.setKind(kind);
    r.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    r.setModel(model);
    r.setApiKey("test-private-key");
    r.setDimensions(dims);
    r.setContextTokens(16000);
    r.setMaxOutputTokens(2000);
    r.setTimeoutSeconds(5);
    return r;
  }

  UUID id(Map<String, Object> map) {
    return UUID.fromString(map.get("id").toString());
  }

  Map<String, Object> config(String kind, String model, int dims) {
    return models.save(null, request(kind, model, dims), "admin");
  }

  void bind(String embedding) {
    var chat = config("CHAT", "chat", 0);
    var embed = config("EMBEDDING", embedding, embedding.endsWith("4") ? 4 : 3);
    models.bind(
        new HashMap<>(Map.of("extract", id(chat), "summary", id(chat), "embedding", id(embed))),
        "admin");
  }

  void ingest() {
    var s =
        documents.acceptText(
            kb,
            "admin",
            "source.md",
            "# Heading\nAlice and Bob research knowledge graphs.",
            "markdown",
            64,
            16);
    ingestion.process(jobs.claim("stage3-test").orElseThrow(), () -> false);
    assertEquals(
        "processed",
        jdbc.queryForObject(
            "SELECT status FROM rag_ingestion_job WHERE id=?", String.class, s.getJobId()));
  }

  String state(UUID gen) {
    return jdbc.queryForObject(
        "SELECT state FROM rag_index_generation WHERE id=?", String.class, gen);
  }

  @Test
  void secretIsEncryptedMaskedVersionedAndAdminOnly() {
    var first = config("CHAT", "chat", 0);
    String serialized = json.writeValueAsString(first);
    assertFalse(serialized.contains("test-private-key"));
    UUID version = id(first);
    String cipher =
        jdbc.queryForObject(
            "SELECT secret_cipher FROM rag_model_version WHERE id=?", String.class, version);
    assertNotEquals("test-private-key", cipher);
    var update = request("CHAT", "changed", 0);
    update.setApiKey("");
    var second = models.save(UUID.fromString(first.get("profileId").toString()), update, "admin");
    assertNotEquals(version, id(second));
    assertEquals(2, second.get("version"));
    assertEquals(true, second.get("keyConfigured"));
    assertThrows(RagAccessException.class, () -> models.list("unknown"));
    var invalid = request("CHAT", "bad", 0);
    invalid.setBaseUrl("http://169.254.169.254/latest");
    assertThrows(IllegalArgumentException.class, () -> models.save(null, invalid, "admin"));
  }

  @Test
  void connectionTestsExerciseAllThreeProtocols() {
    for (String kind : List.of("CHAT", "EMBEDDING", "RERANK")) {
      var m =
          config(
              kind, kind.equals("EMBEDDING") ? "embed3" : "chat", kind.equals("EMBEDDING") ? 3 : 0);
      assertEquals(true, models.test(id(m), "admin").get("success"));
    }
  }

  @Test
  void generationBuildPublishesSeparateCollectionsThenAtomicallySwitchesAndRollsBack() {
    bind("embed3");
    ingest();
    UUID first = id(indexes.create(kb, "admin"));
    assertTrue(indexer.runOnce());
    assertEquals("ready", state(first));
    assertNull(
        jdbc.queryForObject(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    indexes.activate(kb, first, null, "admin");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, first));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id=?", Integer.class, first));
    assertEquals(
        1.0,
        jdbc.queryForObject(
            "SELECT weight FROM rag_index_relation WHERE generation_id=?", Double.class, first));
    bind("embed4");
    UUID second = id(indexes.create(kb, "admin"));
    indexer.runOnce();
    assertEquals("ready", state(second));
    assertEquals(
        first,
        jdbc.queryForObject(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    indexes.activate(kb, second, first, "admin");
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT dimensions FROM rag_index_generation WHERE id=?", Integer.class, second));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT vector_dims(embedding) FROM rag_index_chunk_vector WHERE generation_id=? LIMIT"
                + " 1",
            Integer.class,
            first));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT vector_dims(embedding) FROM rag_index_chunk_vector WHERE generation_id=? LIMIT"
                + " 1",
            Integer.class,
            second));
    assertThrows(RagAccessException.class, () -> indexes.activate(kb, first, first, "admin"));
    indexes.activate(kb, first, second, "admin");
    assertEquals(
        first,
        jdbc.queryForObject(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
  }

  @Test
  void failedNewModelCannotDisturbActiveIndexAndManualRetryDoesNotDoubleWeights() {
    bind("embed3");
    ingest();
    UUID first = id(indexes.create(kb, "admin"));
    indexer.runOnce();
    indexes.activate(kb, first, null, "admin");
    UUID second = id(indexes.create(kb, "admin"));
    failEmbedding.set(true);
    indexer.runOnce();
    assertEquals("failed", state(second));
    assertEquals(
        first,
        jdbc.queryForObject(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    failEmbedding.set(false);
    indexes.retry(kb, second, "admin");
    indexer.runOnce();
    assertEquals("ready", state(second));
    assertEquals(
        1.0,
        jdbc.queryForObject(
            "SELECT weight FROM rag_index_relation WHERE generation_id=?", Double.class, second));
  }

  @Test
  void cancellationsAndStaleLeasesAreDurableAndFenced() {
    bind("embed3");
    ingest();
    UUID gen = id(indexes.create(kb, "admin"));
    indexes.cancel(kb, gen, "admin");
    assertEquals("cancelled", state(gen));
    assertFalse(indexer.runOnce());
    indexes.retry(kb, gen, "admin");
    var lease = indexes.claim().orElseThrow();
    jdbc.update(
        "UPDATE rag_index_generation SET lease_until=CLOCK_TIMESTAMP()-INTERVAL '1 second' WHERE"
            + " id=?",
        gen);
    assertFalse(indexes.heartbeat(lease));
    indexes.fail(lease, "index_lease_lost");
    assertEquals("building", state(gen));
    var replacement = indexes.claim().orElseThrow();
    assertNotEquals(lease.getToken(), replacement.getToken());
    assertThrows(RuntimeException.class, () -> indexes.check(lease));
    indexes.cancel(kb, gen, "admin");
    assertThrows(RuntimeException.class, () -> indexes.check(replacement));
  }

  @Test
  void parallelBuildsRemainIsolatedAndDoNotAccumulateDuplicateWeights() throws Exception {
    bind("embed3");
    ingest();
    UUID a = id(indexes.create(kb, "admin")), b = id(indexes.create(kb, "admin"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var f1 = pool.submit(indexer::runOnce);
      var f2 = pool.submit(indexer::runOnce);
      assertTrue(f1.get(30, TimeUnit.SECONDS));
      assertTrue(f2.get(30, TimeUnit.SECONDS));
    }
    assertEquals("ready", state(a));
    assertEquals("ready", state(b));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id IN (?,?) AND weight=1",
            Integer.class,
            a,
            b));
    UUID other = bases.create("admin", "Other", "").getId();
    try {
      assertThrows(RagAccessException.class, () -> indexes.activate(other, a, null, "admin"));
    } finally {
      bases.delete(other, "admin");
    }
  }

  @Test
  void retriesResumeCompletedChunkCheckpointsAndRejectCorruptVectors() {
    bind("embed3");
    ingest();
    UUID gen = id(indexes.create(kb, "admin"));
    failAt.set(2);
    indexer.runOnce();
    assertEquals("failed", state(gen));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
            Integer.class,
            gen));
    indexes.retry(kb, gen, "admin");
    indexer.runOnce();
    assertEquals("ready", state(gen));
    assertEquals(5, embeddingCalls.get());
    assertEquals(
        1.0,
        jdbc.queryForObject(
            "SELECT weight FROM rag_index_relation WHERE generation_id=?", Double.class, gen));
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                "UPDATE rag_index_chunk_vector SET embedding='[1,2,3,4]'::vector WHERE"
                    + " generation_id=?",
                gen));
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                "UPDATE rag_model_version SET model='tampered' WHERE id=(SELECT embedding_version"
                    + " FROM rag_index_generation WHERE id=?)",
                gen));
  }

  @Test
  void sameDimensionDifferentModelsRemainSeparateUntilExplicitActivation() {
    bind("embed3");
    ingest();
    UUID a = id(indexes.create(kb, "admin"));
    indexer.runOnce();
    indexes.activate(kb, a, null, "admin");
    UUID oldModel =
        jdbc.queryForObject(
            "SELECT embedding_version FROM rag_index_generation WHERE id=?", UUID.class, a);
    bind("other-embed3");
    UUID b = id(indexes.create(kb, "admin"));
    indexer.runOnce();
    assertNotEquals(
        oldModel,
        jdbc.queryForObject(
            "SELECT embedding_version FROM rag_index_generation WHERE id=?", UUID.class, b));
    assertEquals(
        a,
        jdbc.queryForObject(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT generation_id) FROM rag_index_chunk_vector WHERE"
                + " knowledge_base_id=? AND dimensions=3",
            Integer.class,
            kb));
    indexes.activate(kb, b, a, "admin");
  }

  @Test
  void realHttpRejectsNonAdminModelAccessAndReadOnlyIndexWrites() throws Exception {
    jdbc.update(
        "INSERT INTO app_user(username,password_hash) SELECT 'model-reader',password_hash FROM"
            + " app_user WHERE username='admin' ON CONFLICT (username) DO NOTHING");
    bases.grant(kb, "admin", "model-reader", KnowledgeRole.READER);
    var client =
        java.net.http.HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
            .build();
    String root = "http://localhost:" + port;
    var get =
        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models")).GET().build();
    assertEquals(
        401, client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    String csrf =
        json.readTree(
                client
                    .send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                            .GET()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    assertEquals(
        200,
        client
            .send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/login"))
                    .header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            "username=model-reader&password=123456"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode());
    csrf =
        json.readTree(
                client
                    .send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                            .GET()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    assertEquals(
        403, client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(
        403,
        client
            .send(
                java.net.http.HttpRequest.newBuilder(
                        URI.create(root + "/api/knowledge-bases/" + kb + "/indexes"))
                    .header("X-CSRF-TOKEN", csrf)
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        403,
        client
            .send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        403,
        client
            .send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models"))
                    .header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("malformed-json"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void administratorBindingWritesReturnNoContentAndModelReadsNeverExposeKeys() throws Exception {
    config("CHAT", "chat", 0);
    var client =
        java.net.http.HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
            .build();
    String root = "http://localhost:" + port;
    String csrf =
        json.readTree(
                client
                    .send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                            .GET()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    assertEquals(
        200,
        client
            .send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/login"))
                    .header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            "username=admin&password=123456"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            .statusCode());
    csrf =
        json.readTree(
                client
                    .send(
                        java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                            .GET()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    var result =
        client.send(
            java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models/bindings"))
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(204, result.statusCode());
    assertEquals("", result.body());
    var models =
        client.send(
            java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models"))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200, models.statusCode());
    assertFalse(models.body().contains("test-private-key"));
    assertFalse(models.body().contains("secret_cipher"));
  }
}
