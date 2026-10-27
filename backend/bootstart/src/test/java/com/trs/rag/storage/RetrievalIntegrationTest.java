package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.trs.app.rag.*;
import com.trs.app.rag.index.*;
import com.trs.app.rag.ingestion.*;
import com.trs.app.rag.model.*;
import com.trs.app.rag.retrieval.*;
import com.trs.app.rag.retrieval.RetrievalData.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Independent PostgreSQL graph fixtures and actual model wire-protocol retrieval tests. @author
 * agent
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "rag.ingestion.enabled=false",
      "rag.indexing.enabled=false",
      "rag.models.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "rag.models.allowed-hosts=127.0.0.1",
      "rag.models.allow-http=true",
      "rag.retrieval.max-concurrency=2"
    })
class RetrievalIntegrationTest {
  @Container
  static final GenericContainer<?> DB =
      new GenericContainer<>(VectorStorageIntegrationTest.IMAGE)
          .withEnv("POSTGRES_DB", "retrieval")
          .withEnv("POSTGRES_USER", "retrieval")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String url = "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/retrieval";
    for (String k : List.of("spring.datasource.url", "spring.flyway.url")) r.add(k, () -> url);
    for (String k : List.of("spring.datasource.username", "spring.flyway.user"))
      r.add(k, () -> "retrieval");
    for (String k : List.of("spring.datasource.password", "spring.flyway.password"))
      r.add(k, () -> "test-only");
    r.add("rag.vector.dimensions", () -> "3");
    r.add("rag.vector.model", () -> "fixture-3d");
  }

  @Autowired RetrievalService retrieval;
  @Autowired RetrievalRepository repository;
  @Autowired ModelConfigurationService models;
  @Autowired KnowledgeBaseService bases;
  @Autowired DocumentService documents;
  @Autowired JobService jobs;
  @Autowired IngestionProcessor parser;
  @Autowired IndexService indexes;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @LocalServerPort int port;
  final JsonMapper json = JsonMapper.builder().build();
  HttpServer server;
  ExecutorService provider;
  UUID kb, chat;
  Map<String, UUID> chunks = new LinkedHashMap<>();
  List<String> embeddingModels = new CopyOnWriteArrayList<>();
  List<List<String>> embeddingInputs = new CopyOnWriteArrayList<>();
  AtomicInteger keywordCalls = new AtomicInteger();
  AtomicBoolean failEmbedding = new AtomicBoolean();
  AtomicReference<Runnable> switchIndex = new AtomicReference<>();
  volatile CountDownLatch entered, release;

  @BeforeEach
  void setup() throws Exception {
    jdbc.update("DELETE FROM rag_model_binding");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(provider);
    server.createContext(
        "/v1/",
        e -> {
          try {
            var body = json.readTree(e.getRequestBody().readAllBytes());
            if (!"Bearer retrieval-private-key"
                .equals(e.getRequestHeaders().getFirst("Authorization"))) {
              e.sendResponseHeaders(401, -1);
              return;
            }
            String response;
            if (e.getRequestURI().getPath().endsWith("/embeddings")) {
              embeddingModels.add(body.path("model").asText());
              List<String> inputs = new ArrayList<>();
              body.path("input").forEach(v -> inputs.add(v.asText()));
              embeddingInputs.add(inputs);
              if (entered != null) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
              }
              var swap = switchIndex.getAndSet(null);
              if (swap != null) swap.run();
              if (failEmbedding.get()) {
                e.sendResponseHeaders(503, -1);
                return;
              }
              int d = body.path("model").asText().endsWith("4") ? 4 : 3;
              List<Map<String, Object>> data = new ArrayList<>();
              for (int i = 0; i < inputs.size(); i++) {
                List<Double> v =
                    new ArrayList<>(
                        inputs.get(i).contains("knowledge graph")
                            ? List.of(0., 1., 0.)
                            : List.of(1., 0., 0.));
                if (d == 4) v.add(0.);
                data.add(Map.of("index", i, "embedding", v));
              }
              response = json.writeValueAsString(Map.of("data", data));
            } else {
              keywordCalls.incrementAndGet();
              response =
                  json.writeValueAsString(
                      Map.of(
                          "choices",
                          List.of(
                              Map.of(
                                  "finish_reason",
                                  "stop",
                                  "message",
                                  Map.of(
                                      "role",
                                      "assistant",
                                      "content",
                                      "{\"high_level_keywords\":[\"knowledge"
                                          + " graph\"],\"low_level_keywords\":[\"Alice\"]}")))));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
          } catch (Throwable failed) {
          } finally {
            e.close();
          }
        });
    server.start();
    chat = model("CHAT", "keyword", 0);
    models.bind(new HashMap<>(Map.of("extract", chat, "keywords", chat, "summary", chat)), "admin");
    kb = bases.create("admin", "retrieval fixture", "").getId();
    ingest("english.md", "Alice uses LightRAG.");
    ingest("中文.md", "张三使用知识图谱。");
    ingest("图谱.md", "向量检索支持知识融合。");
    ingest("unknown.txt", "Unknown source");
    jdbc.query(
        "SELECT id,content FROM rag_chunk WHERE knowledge_base_id=?",
        rs -> {
          chunks.put(rs.getString("content"), rs.getObject("id", UUID.class));
        },
        kb);
  }

  @AfterEach
  void cleanup() {
    bases.delete(kb, "admin");
    if (server != null) server.stop(0);
    if (provider != null) provider.shutdownNow();
  }

  UUID model(String kind, String name, int d) {
    var r = new ModelRequest();
    r.setName("retrieval-" + UUID.randomUUID());
    r.setKind(kind);
    r.setModel(name);
    r.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    r.setApiKey("retrieval-private-key");
    r.setDimensions(d);
    r.setMaxOutputTokens(1000);
    r.setTimeoutSeconds(5);
    return UUID.fromString(models.save(null, r, "admin").get("id").toString());
  }

  void ingest(String name, String text) {
    documents.acceptText(kb, "admin", name, text, "text", 64, 16);
    parser.process(jobs.claim("retrieval-fixture").orElseThrow(), () -> false);
  }

  UUID uuid(String text) {
    return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  UUID c(int n) {
    return new ArrayList<>(chunks.values()).get(n);
  }

  void vector(String kind, UUID gen, UUID id, int d, String value) {
    jdbc.update(
        "INSERT INTO rag_index_"
            + kind
            + "_vector(knowledge_base_id,generation_id,object_id,dimensions,embedding) VALUES"
            + " (?,?,?,?,?::vector)",
        kb,
        gen,
        id,
        d,
        value);
  }

  UUID seed(int dimensions, String prefix) {
    UUID embed = model("EMBEDDING", "embed" + dimensions, dimensions);
    models.bind(Map.of("embedding", embed), "admin");
    UUID gen = UUID.fromString(indexes.create(kb, "admin").get("id").toString());
    UUID a = uuid(prefix + "Alice"), l = uuid(prefix + "LightRAG"), g = uuid(prefix + "知识图谱");
    UUID c1 = chunks.get("Alice uses LightRAG."),
        c2 = chunks.get("张三使用知识图谱。"),
        c3 = chunks.get("向量检索支持知识融合。");
    insertNode(gen, a, prefix + "Alice", "person", "Alice uses LightRAG.", List.of(c1));
    insertNode(gen, l, prefix + "LightRAG", "organization", "A RAG framework.", List.of(c1, c2));
    insertNode(gen, g, prefix + "知识图谱", "concept", "知识融合。", List.of(c3));
    String z = dimensions == 4 ? ",0]" : "]";
    vector("entity", gen, a, dimensions, "[1,0,0" + z);
    vector("entity", gen, l, dimensions, "[0.8,0.6,0" + z);
    vector("entity", gen, g, dimensions, "[0,0,1" + z);
    UUID r1 = uuid(prefix + "r1"), r2 = uuid(prefix + "r2");
    insertEdge(gen, r1, l, g, "支持图谱。", "graph", 2, List.of(c3));
    insertEdge(gen, r2, a, l, "Uses LightRAG.", "uses", 1, List.of(c1));
    vector("relation", gen, r1, dimensions, "[0,1,0" + z);
    vector("relation", gen, r2, dimensions, "[0,0.8,0.6" + z);
    for (var entry : chunks.entrySet()) {
      String value =
          entry.getKey().equals("Alice uses LightRAG.")
              ? "[1,0,0"
              : entry.getKey().equals("张三使用知识图谱。")
                  ? "[0.8,0.6,0"
                  : entry.getKey().equals("向量检索支持知识融合。") ? "[0,1,0" : "[0,0,1";
      vector("chunk", gen, entry.getValue(), dimensions, value + z);
    }
    jdbc.update(
        "UPDATE rag_index_chunk SET completed=TRUE,extraction='{}'::JSONB WHERE generation_id=?",
        gen);
    jdbc.update("UPDATE rag_index_generation SET state='ready' WHERE id=?", gen);
    return gen;
  }

  void insertNode(UUID gen, UUID id, String name, String type, String desc, List<UUID> sources) {
    jdbc.update(
        "INSERT INTO"
            + " rag_index_entity(knowledge_base_id,generation_id,id,name,entity_type,description,source_ids)"
            + " VALUES (?,?,?,?,?,?,?::JSONB)",
        kb,
        gen,
        id,
        name,
        type,
        desc,
        json.writeValueAsString(sources));
  }

  void insertEdge(
      UUID gen,
      UUID id,
      UUID a,
      UUID b,
      String desc,
      String keywords,
      int weight,
      List<UUID> sources) {
    if (a.toString().compareTo(b.toString()) > 0) {
      UUID tmp = a;
      a = b;
      b = tmp;
    }
    jdbc.update(
        "INSERT INTO"
            + " rag_index_relation(knowledge_base_id,generation_id,id,source_id,target_id,description,keywords,weight,source_ids)"
            + " VALUES (?,?,?,?,?,?,?,?,?::JSONB)",
        kb,
        gen,
        id,
        a,
        b,
        desc,
        keywords,
        weight,
        json.writeValueAsString(sources));
  }

  RetrievalRequest request(String mode) {
    var r = new RetrievalRequest();
    r.setQuery("question");
    r.setMode(mode);
    r.setTopK(2);
    r.setChunkTopK(2);
    return r;
  }

  @Test
  void fiveModesUseCorrectBranchesAndOneEmbeddingBatch() {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    for (String mode : List.of("naive", "local", "global", "hybrid", "mix")) {
      int before = embeddingModels.size();
      var result = retrieval.query(kb, "admin", request(mode));
      assertEquals(gen, result.getGenerationId());
      assertEquals(before + 1, embeddingModels.size());
      assertEquals("success", result.getStatus());
      assertTrue((int) result.getMetadata().get("embeddingCalls") == 1);
      if (mode.equals("naive")) {
        assertTrue(result.getEntities().isEmpty());
        assertEquals(2, result.getChunks().size());
      }
      if (mode.equals("local")) {
        assertEquals(1.0, result.getEntities().get(0).getScore(), 1e-6);
        assertEquals(.8, result.getEntities().get(1).getScore(), 1e-6);
      }
      if (mode.equals("local"))
        assertEquals(
            List.of("Alice", "LightRAG"),
            result.getEntities().stream().map(Node::getName).toList());
      if (mode.equals("global"))
        assertEquals(
            List.of("LightRAG", "知识图谱", "Alice"),
            result.getEntities().stream().map(Node::getName).toList());
      if (mode.equals("mix"))
        assertEquals(
            List.of("entities", "relations", "chunks"), result.getMetadata().get("branches"));
    }
    assertEquals(4, keywordCalls.get());
    assertEquals(3, embeddingInputs.getLast().size());
  }

  @Test
  void modelAndGenerationStayCapturedAcrossGlobalRebindAndAtomicActivation() {
    UUID old = seed(3, "");
    indexes.activate(kb, old, null, "admin");
    UUID next = seed(4, "NEW ");
    switchIndex.set(() -> indexes.activate(kb, next, old, "admin"));
    var result = retrieval.query(kb, "admin", request("mix"));
    assertEquals(old, result.getGenerationId());
    assertEquals("embed3", embeddingModels.getLast());
    assertTrue(result.getEntities().stream().noneMatch(n -> n.getName().startsWith("NEW")));
    assertEquals(
        next,
        jdbc.queryForObject(
            "SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
  }

  @Test
  void weightAndVectorSourceSelectionDeduplicateAndZeroDisablesOnlyGraphChunks() {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    var request = request("hybrid");
    request.setHighLevelKeywords(List.of("knowledge graph"));
    request.setLowLevelKeywords(List.of("Alice"));
    request.setChunkSelection("WEIGHT");
    var weight = retrieval.query(kb, "admin", request);
    assertEquals(0, keywordCalls.get());
    assertEquals(3, weight.getChunks().size());
    assertEquals(3, weight.getChunks().stream().map(c -> c.get("chunk_id")).distinct().count());
    request.setRelatedChunkNumber(0);
    assertTrue(retrieval.query(kb, "admin", request).getChunks().isEmpty());
    request.setMode("mix");
    assertEquals(2, retrieval.query(kb, "admin", request).getChunks().size());
  }

  @Test
  void sharedEvidenceRetainsAllOriginsAndScopedUnicodeSourcePreview() {
    var second = documents.acceptText(kb, "admin", "共享.md", "张三使用知识图谱。", "text", 64, 16);
    parser.process(jobs.claim("shared-source").orElseThrow(), () -> false);
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    var result = retrieval.query(kb, "admin", request("naive"));
    var chunk =
        result.getChunks().stream()
            .filter(r -> r.get("content").equals("张三使用知识图谱。"))
            .findFirst()
            .orElseThrow();
    var origins = (List<Origin>) chunk.get("sources");
    assertEquals(2, origins.size());
    for (var origin : origins) {
      var source =
          repository.source(
              kb,
              gen,
              UUID.fromString(chunk.get("chunk_id").toString()),
              UUID.fromString(origin.getDocumentId()),
              UUID.fromString(origin.getRevisionId()),
              origin.getChunkOrder(),
              "admin");
      assertEquals("张三使用知识图谱。", source.get("excerpt"));
    }
    assertThrows(
        RagAccessException.class,
        () ->
            repository.source(
                kb,
                gen,
                UUID.fromString(chunk.get("chunk_id").toString()),
                UUID.randomUUID(),
                second.getRevisionId(),
                0,
                "admin"));
    assertThrows(RagAccessException.class, () -> retrieval.query(kb, "unknown", request("naive")));
  }

  @Test
  void missingIndexEmptyMatchesAndProviderFailureRemainDistinct() {
    assertThrows(RetrievalFailure.class, () -> retrieval.query(kb, "admin", request("naive")));
    assertTrue(embeddingModels.isEmpty());
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    var request = request("naive");
    request.setCosineThreshold(1);
    assertEquals(1, retrieval.query(kb, "admin", request).getChunks().size());
    jdbc.update(
        "UPDATE rag_index_chunk_vector SET embedding='[0,0,1]'::vector WHERE generation_id=?", gen);
    var empty = retrieval.query(kb, "admin", request);
    assertEquals("empty", empty.getStatus());
    assertTrue(empty.getChunks().isEmpty());
    assertEquals("", empty.getContext());
    failEmbedding.set(true);
    assertThrows(ModelFailure.class, () -> retrieval.query(kb, "admin", request));
  }

  @Test
  void databaseFailureIsNotReturnedAsEmptyResults() {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    jdbc.execute("ALTER TABLE rag_index_chunk_vector RENAME TO unavailable_vectors");
    try {
      var failure =
          assertThrows(
              RetrievalFailure.class, () -> retrieval.query(kb, "admin", request("naive")));
      assertEquals(503, failure.getStatus());
    } finally {
      jdbc.execute("ALTER TABLE unavailable_vectors RENAME TO rag_index_chunk_vector");
    }
  }

  @Test
  void concurrentQueryAdmissionIsBoundedWithoutBlockingDatabaseTransactions() throws Exception {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    entered = new CountDownLatch(2);
    release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> retrieval.query(kb, "admin", request("naive")));
      var b = pool.submit(() -> retrieval.query(kb, "admin", request("naive")));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var failure =
            assertThrows(
                RetrievalFailure.class, () -> retrieval.query(kb, "admin", request("naive")));
        assertEquals(429, failure.getStatus());
      } finally {
        release.countDown();
      }
      assertEquals("success", a.get(10, TimeUnit.SECONDS).getStatus());
      assertEquals("success", b.get(10, TimeUnit.SECONDS).getStatus());
    }
  }

  @Test
  void realHttpRequiresSessionCsrfAndValidatesMode() throws Exception {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String root = "http://localhost:" + port;
    String url = root + "/api/knowledge-bases/" + kb + "/retrieval";
    String csrf =
        json.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(root + "/api/auth/login"))
                    .header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=123456"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    csrf =
        json.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    assertEquals(
        403,
        client
            .send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"query\":\"question\",\"mode\":\"naive\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        400,
        client
            .send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("X-CSRF-TOKEN", csrf)
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"query\":\"question\",\"mode\":\"bypass\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"query\":\"question\",\"mode\":\"mix\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertEquals(gen.toString(), json.readTree(response.body()).path("generationId").asText());
    assertFalse(response.body().contains("retrieval-private-key"));
  }

  @Test
  void readerRevocationDuringModelCallStopsEvidenceAndSourceDisclosure() {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    jdbc.update(
        "INSERT INTO app_user(username,password_hash) SELECT 'search-reader',password_hash FROM"
            + " app_user WHERE username='admin' ON CONFLICT (username) DO NOTHING");
    bases.grant(kb, "admin", "search-reader", KnowledgeRole.READER);
    assertEquals("success", retrieval.query(kb, "search-reader", request("naive")).getStatus());
    switchIndex.set(() -> bases.revoke(kb, "admin", "search-reader"));
    assertThrows(
        RagAccessException.class, () -> retrieval.query(kb, "search-reader", request("naive")));
  }

  @Test
  void referencesStayOnCapturedRevisionAfterDocumentReplacement() {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    var result = retrieval.query(kb, "admin", request("naive"));
    var evidence =
        result.getChunks().stream()
            .filter(c -> c.get("content").equals("张三使用知识图谱。"))
            .findFirst()
            .orElseThrow();
    Origin original = ((List<Origin>) evidence.get("sources")).getFirst();
    var revision = documents.acceptText(kb, "admin", "中文.md", "更新🙂文档。", "text", 64, 16);
    parser.process(jobs.claim("revision-replacement").orElseThrow(), () -> false);
    var excerpt =
        repository.source(
            kb,
            gen,
            UUID.fromString(evidence.get("chunk_id").toString()),
            UUID.fromString(original.getDocumentId()),
            UUID.fromString(original.getRevisionId()),
            original.getChunkOrder(),
            "admin");
    assertEquals("张三使用知识图谱。", excerpt.get("excerpt"));
    assertThrows(
        RagAccessException.class,
        () ->
            repository.source(
                kb,
                gen,
                UUID.fromString(evidence.get("chunk_id").toString()),
                revision.getDocumentId(),
                revision.getRevisionId(),
                0,
                "admin"));
  }

  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  @Test
  void validationNeverLogsRejectedQueryText(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String root = "http://localhost:" + port;
    String csrf =
        json.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    client.send(
        HttpRequest.newBuilder(URI.create(root + "/api/auth/login"))
            .header("X-CSRF-TOKEN", csrf)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=123456"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    csrf =
        json.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    String marker = "private-query-marker-";
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(root + "/api/knowledge-bases/" + kb + "/retrieval"))
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            Map.of("query", marker.repeat(500), "mode", "naive"))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, response.statusCode());
    assertFalse(output.getAll().contains(marker));
  }

  @Test
  void filteredHnswCanUnderfillWhileScopedExactRecallRemainsComplete() {
    UUID target = seed(3, ""), other = seed(3, "Other ");
    UUID source = chunks.get("Alice uses LightRAG.");
    for (int i = 1; i <= 50; i++) {
      UUID id = uuid("ann-target-" + i);
      insertNode(target, id, "Target " + i, "concept", "Scoped ANN evaluation", List.of(source));
      vector("entity", target, id, 3, "[" + (.001 * i) + ",1,0]");
    }
    for (int i = 1; i <= 200; i++) {
      UUID id = uuid("ann-other-" + i);
      insertNode(
          other, id, "Other candidate " + i, "concept", "Different generation", List.of(source));
      vector("entity", other, id, 3, "[1," + (.00001 * i) + ",0]");
    }
    var exact =
        jdbc.queryForList(
            "SELECT object_id::TEXT FROM rag_index_entity_vector WHERE knowledge_base_id=? AND"
                + " generation_id=? ORDER BY embedding <=> '[1,0,0]'::vector,object_id LIMIT 10",
            String.class,
            kb,
            target);
    jdbc.execute(
        "CREATE INDEX stage4_ann_test ON rag_index_entity_vector USING hnsw ((embedding::vector(3))"
            + " vector_cosine_ops) WHERE dimensions=3");
    try {
      var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
      var approximate =
          template.execute(
              status -> {
                jdbc.execute("SET LOCAL enable_seqscan=off");
                jdbc.execute("SET LOCAL enable_sort=off");
                jdbc.execute("SET LOCAL hnsw.ef_search=10");
                jdbc.execute("SET LOCAL hnsw.iterative_scan=off");
                String query =
                    "SELECT object_id::TEXT FROM rag_index_entity_vector WHERE dimensions=3 AND"
                        + " knowledge_base_id=? AND generation_id=? ORDER BY embedding::vector(3)"
                        + " <=> '[1,0,0]'::vector(3) LIMIT 10";
                var plan =
                    jdbc.queryForList("EXPLAIN (COSTS OFF) " + query, String.class, kb, target);
                assertTrue(String.join("\n", plan).contains("stage4_ann_test"), plan.toString());
                return jdbc.queryForList(query, String.class, kb, target);
              });
      assertEquals(10, exact.size());
      assertNotNull(approximate);
      assertTrue(approximate.size() < 10);
      long matching = approximate.stream().filter(exact::contains).count();
      System.out.println(
          "STAGE4_FILTERED_ANN exact="
              + exact.size()
              + " ann="
              + approximate.size()
              + " Recall@10="
              + matching / 10.0
              + "; independent generations, ef_search=10, iterative_scan=off");
    } finally {
      jdbc.execute("DROP INDEX stage4_ann_test");
    }
  }

  @Test
  void localEdgeDegreeOutranksEvenAMuchHigherRelationshipWeight() {
    UUID gen = seed(3, "");
    indexes.activate(kb, gen, null, "admin");
    UUID extra = uuid("degree-neighbor");
    UUID evidence = chunks.get("向量检索支持知识融合。");
    insertNode(gen, extra, "Neighbor", "concept", "Additional adjacent entity", List.of(evidence));
    vector("entity", gen, extra, 3, "[0,0,1]");
    UUID edge = uuid("degree-extra-edge");
    insertEdge(
        gen,
        edge,
        uuid("知识图谱"),
        extra,
        "Extra degree contribution",
        "neighbor",
        1,
        List.of(evidence));
    vector("relation", gen, edge, 3, "[0,0,1]");
    jdbc.update(
        "UPDATE rag_index_relation SET weight=100 WHERE knowledge_base_id=? AND generation_id=? AND"
            + " id=?",
        kb,
        gen,
        uuid("r2"));
    var result = retrieval.query(kb, "admin", request("local"));
    assertEquals(2, result.getRelations().size());
    assertEquals(
        List.of("LightRAG", "知识图谱"),
        List.of(
            result.getRelations().getFirst().getSource(),
            result.getRelations().getFirst().getTarget()));
    assertEquals(4, result.getRelations().getFirst().getRank());
    assertEquals(3, result.getRelations().get(1).getRank());
    assertEquals(100.0, result.getRelations().get(1).getWeight());
  }
}
