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
            "rag.models.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class ModelIndexIntegrationTest {
    @Container
    static final GenericContainer<?> DB = new GenericContainer<>(VectorStorageIntegrationTest.IMAGE)
            .withEnv("POSTGRES_DB", "models")
            .withEnv("POSTGRES_USER", "models")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/models";
        for (String k : List.of("spring.datasource.url", "spring.flyway.url")) r.add(k, () -> url);
        for (String k : List.of("spring.datasource.username", "spring.flyway.user")) r.add(k, () -> "models");
        for (String k : List.of("spring.datasource.password", "spring.flyway.password")) r.add(k, () -> "test-only");
        r.add("rag.vector.dimensions", () -> "3");
        r.add("rag.vector.model", () -> "fixture-3d");
    }

    @Autowired
    ModelConfigurationService models;

    @Autowired
    IndexService indexes;

    @Autowired
    IndexProcessor indexer;

    @Autowired
    KnowledgeBaseService bases;

    @Autowired
    DocumentService documents;

    @Autowired
    JobService jobs;

    @Autowired
    IngestionProcessor ingestion;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DocumentWorkflowService workflow;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    HttpServer server;
    UUID kb;
    AtomicInteger embeddingCalls = new AtomicInteger();
    AtomicBoolean failEmbedding = new AtomicBoolean();
    AtomicInteger failAt = new AtomicInteger(-1);
    AtomicReference<tools.jackson.databind.JsonNode> lastChatRequest = new AtomicReference<>();
    AtomicBoolean holdExtraction = new AtomicBoolean(), truncateExtraction = new AtomicBoolean();
    AtomicBoolean orphanRelations = new AtomicBoolean(), repairEndpoints = new AtomicBoolean();
    AtomicBoolean omitAttributes = new AtomicBoolean();
    AtomicBoolean typedEntities = new AtomicBoolean();
    AtomicInteger invalidEntityAt = new AtomicInteger(-1);
    AtomicBoolean repeatInvalidEntity = new AtomicBoolean();
    AtomicBoolean truncateCorrection = new AtomicBoolean();
    AtomicInteger extractionCalls = new AtomicInteger();
    CountDownLatch extractionSent = new CountDownLatch(1), extractionRelease = new CountDownLatch(1);
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM rag_knowledge_base_model_binding");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", ex -> {
            try {
                var body = json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("chat/completions")) lastChatRequest.set(body);
                String response;
                assertEquals("Bearer test-private-key", ex.getRequestHeaders().getFirst("Authorization"));
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
                        data.add(Map.of(
                                "index",
                                i,
                                "object",
                                "embedding",
                                "embedding",
                                d == 4 ? List.of(1.0, .2, .3, .4) : List.of(1.0, .2, .3)));
                    response = json.writeValueAsString(Map.of(
                            "object",
                            "list",
                            "data",
                            data,
                            "model",
                            body.path("model").asText(),
                            "usage",
                            Map.of("prompt_tokens", 1, "total_tokens", 1)));
                } else if (path.endsWith("rerank")) response = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
                else if (body.path("stream").asBoolean()) {
                    int extractionCall = extractionCalls.incrementAndGet();
                    if (!"json_object"
                            .equals(body.path("response_format").path("type").asText())) {
                        ex.sendResponseHeaders(400, -1);
                        return;
                    }
                    ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    String first =
                            "{\"entities\":[{\"name\":\"Alice\",\"type\":\"人\",\"description\":\"Researcher\",\"attributes\":{\"职业\":\"研究员\"}}";
                    String rest =
                            ",{\"name\":\"Bob\",\"type\":\"人\",\"description\":\"Scientist\",\"attributes\":{}}],\"relations\":[{\"source\":\"Alice\",\"target\":\"Bob\",\"type\":\"合作\",\"keywords\":\"research\",\"description\":\"Collaborate\"}]}";
                    if (orphanRelations.get()) {
                        if (extractionCall == 1) {
                            rest =
                                    "],\"relations\":[{\"source\":\"Alice\",\"target\":\"Bob\",\"type\":\"合作\",\"keywords\":\"research\",\"description\":\"Collaborate\"}]}";
                        } else {
                            first = repairEndpoints.get()
                                    ? "{\"entities\":[{\"name\":\"Bob\",\"type\":\"人\",\"description\":\"Scientist\",\"attributes\":{}}"
                                    : "{\"entities\":[";
                            rest = repairEndpoints.get()
                                    ? "],\"relations\":[]}"
                                    : "],\"relations\":[{\"source\":\"Alice\",\"target\":\"Bob\",\"type\":\"合作\",\"keywords\":\"research\",\"description\":\"Collaborate\"}]}";
                        }
                    }
                    if (omitAttributes.get()) {
                        first = first.replace(",\"attributes\":{\"职业\":\"研究员\"}", "");
                        rest = rest.replace(",\"attributes\":{}", "");
                    }
                    if (typedEntities.get()) {
                        first =
                                "{\"entities\":[{\"name\":\"长安\",\"type\":\"地\",\"description\":\"城市\",\"attributes\":[{\"name\":\"人口\",\"type\":\"integer\",\"value\":35}]}";
                        rest =
                                ",{\"name\":\"长安\",\"type\":\"网\",\"description\":\"企业\",\"attributes\":[]}],\"relations\":[{\"source\":{\"name\":\"长安\",\"type\":\"网\"},\"target\":{\"name\":\"长安\",\"type\":\"地\"},\"type\":\"位于\",\"keywords\":\"城市,企业\",\"description\":\"企业位于城市\"}]}";
                    }
                    if (extractionCall == invalidEntityAt.get()
                            || repeatInvalidEntity.get() && extractionCall >= invalidEntityAt.get())
                        first = first.replace("\"name\":\"Alice\",", "");
                    for (String piece : List.of(first, rest)) {
                        String event = "data: "
                                + json.writeValueAsString(
                                        Map.of("choices", List.of(Map.of("delta", Map.of("content", piece)))))
                                + "\n\n";
                        ex.getResponseBody()
                                .write(com.trs.rag.model.OpenAiFixtures.events(event)
                                        .getBytes(StandardCharsets.UTF_8));
                        ex.getResponseBody().flush();
                        if (piece.equals(first)) {
                            extractionSent.countDown();
                            if (holdExtraction.get()) extractionRelease.await(15, TimeUnit.SECONDS);
                        }
                    }
                    if (!truncateExtraction.get() && !(truncateCorrection.get() && extractionCall > 1))
                        ex.getResponseBody()
                                .write(com.trs.rag.model.OpenAiFixtures.events(
                                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                    ex.close();
                    return;
                } else {
                    String answer = "entity<|#|>Alice<|#|>person<|#|>Researcher\n"
                            + "entity<|#|>Bob<|#|>person<|#|>Scientist\n"
                            + "relation<|#|>Alice<|#|>Bob<|#|>research<|#|>Collaborate\n"
                            + "<|COMPLETE|>";
                    response = json.writeValueAsString(Map.of(
                            "id",
                            "fixture",
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "chat",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "message",
                                    Map.of("role", "assistant", "content", answer),
                                    "finish_reason",
                                    "stop")),
                            "usage",
                            Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
                }
                byte[] bytes = com.trs.rag.model.OpenAiFixtures.json(response).getBytes(StandardCharsets.UTF_8);
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
        extractionRelease.countDown();
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
                kb, new HashMap<>(Map.of("extract", id(chat), "summary", id(chat), "embedding", id(embed))), "admin");
    }

    void ingest() {
        var s = documents.acceptText(
                kb, "admin", "source.md", "# Heading\nAlice and Bob research knowledge graphs.", "markdown", 64, 16);
        ingestion.process(jobs.claim("stage3-test").orElseThrow(), () -> false);
        assertEquals(
                "processed",
                jdbc.queryForObject("SELECT status FROM rag_ingestion_job WHERE id=?", String.class, s.getJobId()));
    }

    String state(UUID gen) {
        return jdbc.queryForObject("SELECT state FROM rag_index_generation WHERE id=?", String.class, gen);
    }

    void advanceDocuments() {
        Long revision = jdbc.queryForObject(
                "SELECT revision FROM rag_document_index_queue WHERE knowledge_base_id=?", Long.class, kb);
        workflow.advance(kb, revision);
    }

    UUID automaticGeneration() {
        return jdbc.queryForObject(
                "SELECT generation_id FROM rag_document_index_queue WHERE knowledge_base_id=?", UUID.class, kb);
    }

    @Test
    void emptyPublishedBaselineCanStageAndPublishWithoutModelCalls() {
        bind("embed3");
        ingest();
        UUID next = id(indexes.create(kb, "admin"));
        var lease = indexes.claim().orElseThrow();
        assertNull(indexes.reusableGraph(lease));
        for (var chunk : indexes.chunks(lease))
            indexes.checkpoint(lease, (UUID) chunk.get("chunk_id"), new Extraction(), new float[] {1, 0, 0});
        indexes.complete(lease, new GraphAssembler.Graph(List.of(), List.of()));
        assertEquals("ready", state(next));
    }

    @Test
    void rowRunCopiesUnchangedGraphAndDeleteRecomputesSharedContributions() {
        bind("embed3");
        var a = documents.acceptText(kb, "admin", "incremental-a.txt", "Alice researches graphs.", "text", 64, 16);
        var b = documents.acceptText(kb, "admin", "incremental-b.txt", "Bob researches networks.", "text", 64, 16);
        ingestion.process(jobs.claim("incremental-test").orElseThrow());
        ingestion.process(jobs.claim("incremental-test").orElseThrow());
        advanceDocuments();
        assertTrue(indexer.runOnce());
        advanceDocuments();
        UUID original =
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb);
        int calls = embeddingCalls.get(), extracts = extractionCalls.get();
        workflow.action(kb, b.getDocumentId(), "admin", "run");
        advanceDocuments();
        UUID next = automaticGeneration();
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(next));
        assertEquals(calls, embeddingCalls.get(), "Unchanged objects must not call the vector model");
        assertEquals(extracts, extractionCalls.get());
        assertEquals(
                original,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity n JOIN rag_index_entity old"
                                + " ON old.knowledge_base_id=n.knowledge_base_id AND old.id=n.id AND old.generation_id=?"
                                + " JOIN rag_index_entity_vector v ON v.knowledge_base_id=n.knowledge_base_id AND v.generation_id=n.generation_id AND v.object_id=n.id"
                                + " JOIN rag_index_entity_vector ov ON ov.knowledge_base_id=old.knowledge_base_id AND ov.generation_id=old.generation_id AND ov.object_id=old.id"
                                + " WHERE n.generation_id=? AND n.contribution_hash=old.contribution_hash AND n.description=old.description AND v.embedding=ov.embedding",
                        Integer.class,
                        original,
                        next));
        advanceDocuments();
        workflow.action(kb, b.getDocumentId(), "admin", "rebuild");
        advanceDocuments();
        assertTrue(indexer.runOnce());
        assertEquals(calls + 1, embeddingCalls.get(), "Same extraction only recomputes the selected chunk vector");
        advanceDocuments();
        calls = embeddingCalls.get();
        workflow.action(kb, b.getDocumentId(), "admin", "delete");
        advanceDocuments();
        UUID remaining = automaticGeneration();
        assertTrue(indexer.runOnce());
        assertEquals(calls + 3, embeddingCalls.get(), "Shared nodes and relationship lose the deleted source");
        assertEquals(
                1.0,
                jdbc.queryForObject(
                        "SELECT weight FROM rag_index_relation WHERE generation_id=?", Double.class, remaining));
        advanceDocuments();
        assertEquals("indexed", documents.get(kb, "admin", a.getDocumentId()).getStatus());
    }

    @Test
    void addingDocumentWithDifferentEntitiesReusesPublishedObjects() {
        bind("embed3");
        documents.acceptText(kb, "admin", "existing.txt", "Alice and Bob work together.", "text", 64, 16);
        ingestion.process(jobs.claim("incremental-test").orElseThrow());
        advanceDocuments();
        assertTrue(indexer.runOnce());
        advanceDocuments();
        int before = embeddingCalls.get();
        typedEntities.set(true);
        var added = documents.acceptText(kb, "admin", "new.txt", "长安公司位于长安。", "text", 64, 16);
        ingestion.process(jobs.claim("incremental-test").orElseThrow());
        workflow.action(kb, added.getDocumentId(), "admin", "run");
        advanceDocuments();
        UUID next = automaticGeneration();
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(next));
        assertEquals(before + 4, embeddingCalls.get(), "Only new chunk, two new entities and new relationship");
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, next));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id=?", Integer.class, next));
    }

    @Test
    void stagedBatchesStayUnpublishedAndRetryFencesOldWriterAndClearsPartialGraph() {
        bind("embed3");
        ingest();
        UUID active = id(indexes.create(kb, "admin"));
        assertTrue(indexer.runOnce());
        indexes.activate(kb, active, null, "admin");
        UUID next = id(indexes.create(kb, "admin"));
        var lease = indexes.claim().orElseThrow();
        UUID chunk = (UUID) indexes.chunks(lease).getFirst().get("chunk_id");
        indexes.checkpoint(lease, chunk, new Extraction(), new float[] {1, 0, 0});
        var nodes = new ArrayList<GraphAssembler.Node>();
        for (int i = 0; i < 65; i++) {
            var node = new GraphAssembler.Node();
            node.setId(EntityIdentity.id("node-" + i, "物"));
            node.setName("node-" + i);
            node.setType("物");
            node.setDescription("fact");
            node.setSources(List.of(chunk));
            node.setVector(new float[] {1, 0, 0});
            nodes.add(node);
        }
        nodes.getLast().setVector(new float[] {1, 0});
        var graph = new GraphAssembler.Graph(nodes, List.of());
        assertEquals(
                "embedding_dimension_mismatch",
                assertThrows(ModelFailure.class, () -> indexes.complete(lease, graph))
                        .getCode());
        assertEquals(
                64,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, next));
        assertEquals("building", state(next));
        assertEquals(
                active,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        indexes.cancel(kb, next, "admin");
        assertEquals(
                "index_lease_lost",
                assertThrows(ModelFailure.class, () -> indexes.complete(lease, graph))
                        .getCode());
        indexes.fail(lease, "index_cancelled");
        indexes.retry(kb, next, "admin");
        var replacement = indexes.claim().orElseThrow();
        nodes.getLast().setVector(new float[] {1, 0, 0});
        indexes.complete(replacement, graph);
        assertEquals("ready", state(next));
        assertEquals(
                65,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, next));
        assertEquals(
                65,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity_vector WHERE generation_id=?", Integer.class, next));
        assertEquals(
                active,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    }

    @Test
    void rowRunDoesNotSilentlyRebuildOtherDocumentsAfterModelChange() {
        bind("embed3");
        var a = documents.acceptText(kb, "admin", "version-a.txt", "Alice researches graphs.", "text", 64, 16);
        documents.acceptText(kb, "admin", "version-b.txt", "Bob researches networks.", "text", 64, 16);
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        advanceDocuments();
        assertTrue(indexer.runOnce());
        advanceDocuments();
        UUID active =
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb);
        int before = extractionCalls.get();
        bind("embed4");
        workflow.action(kb, a.getDocumentId(), "admin", "run");
        var failure = assertThrows(ModelFailure.class, this::advanceDocuments);
        assertEquals("index_document_scope_incompatible", failure.getCode());
        assertEquals(
                active,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        assertEquals(before, extractionCalls.get());
    }

    @Test
    void rowRunResumesOnlySuccessfulChunkCheckpointsFromCancelledGeneration() {
        bind("embed3");
        var a = documents.acceptText(
                kb,
                "admin",
                "resume.txt",
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(i -> "distinctword" + i)
                        .collect(java.util.stream.Collectors.joining(" ")),
                "text",
                64,
                16);
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        advanceDocuments();
        var lease = indexes.claim().orElseThrow();
        var chunks = indexes.chunks(lease);
        assertTrue(chunks.size() > 1);
        indexes.checkpoint(
                lease, (UUID) chunks.getFirst().get("chunk_id"), new Extraction(), new float[] {1, .2f, .3f});
        workflow.action(kb, a.getDocumentId(), "admin", "pause");
        workflow.action(kb, a.getDocumentId(), "admin", "run");
        advanceDocuments();
        UUID generation = automaticGeneration();
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
                        Integer.class,
                        generation));
        assertTrue(indexer.runOnce());
        assertEquals((chunks.size() - 1) * 2, extractionCalls.get());
        advanceDocuments();
        assertEquals("indexed", documents.get(kb, "admin", a.getDocumentId()).getStatus());
    }

    @Test
    void runningOneQueuedDocumentDoesNotStartAnotherQueuedDocument() {
        bind("embed3");
        var a = documents.acceptText(kb, "admin", "queued-a.txt", "Alice researches graphs.", "text", 64, 16);
        var b = documents.acceptText(kb, "admin", "queued-b.txt", "Bob researches networks.", "text", 64, 16);
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        workflow.action(kb, a.getDocumentId(), "admin", "run");
        advanceDocuments();
        assertEquals(
                List.of(a.getDocumentId()),
                jdbc.queryForList(
                        "SELECT document_id FROM rag_index_document WHERE generation_id=?",
                        UUID.class,
                        automaticGeneration()));
        assertEquals("processed", documents.get(kb, "admin", b.getDocumentId()).getStatus());
        assertTrue(indexer.runOnce());
        advanceDocuments();
        assertTrue(workflow.pending().stream().noneMatch(q -> kb.equals(q.get("knowledge_base_id"))));
        assertEquals("processed", documents.get(kb, "admin", b.getDocumentId()).getStatus());
    }

    @Test
    void runningOneFailedDocumentDoesNotRunOtherFailedDocuments() {
        bind("embed3");
        var a = documents.acceptText(kb, "admin", "failed-a.txt", "Alice researches graphs.", "text", 64, 16);
        var b = documents.acceptText(kb, "admin", "failed-b.txt", "Bob researches networks.", "text", 64, 16);
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        jdbc.update("UPDATE rag_document SET pipeline_error='model_http_error' WHERE knowledge_base_id=?", kb);
        workflow.action(kb, a.getDocumentId(), "admin", "run");
        advanceDocuments();
        UUID generation = automaticGeneration();
        assertEquals(
                List.of(a.getDocumentId()),
                jdbc.queryForList(
                        "SELECT document_id FROM rag_index_document WHERE knowledge_base_id=? AND generation_id=?",
                        UUID.class,
                        kb,
                        generation));
        assertTrue(indexer.runOnce());
        advanceDocuments();
        assertEquals("indexed", documents.get(kb, "admin", a.getDocumentId()).getStatus());
        assertEquals("failed", documents.get(kb, "admin", b.getDocumentId()).getStatus());
    }

    @Test
    void rowRebuildOnlyExtractsSelectedDocumentAndRunReusesCompletedCheckpoints() {
        bind("embed3");
        var a = documents.acceptText(kb, "admin", "stable.txt", "Alice researches graphs.", "text", 64, 16);
        var b = documents.acceptText(kb, "admin", "selected.txt", "Bob researches networks.", "text", 64, 16);
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        ingestion.process(jobs.claim("scope-test").orElseThrow());
        advanceDocuments();
        assertTrue(indexer.runOnce());
        advanceDocuments();
        int before = extractionCalls.get();
        workflow.action(kb, b.getDocumentId(), "admin", "rebuild");
        advanceDocuments();
        UUID generation = automaticGeneration();
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_document WHERE generation_id=?", Integer.class, generation));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
                        Integer.class,
                        generation));
        assertTrue(indexer.runOnce());
        assertEquals(2, extractionCalls.get() - before, "Only selected chunk's extract and glean calls");
        advanceDocuments();
        assertEquals("indexed", documents.get(kb, "admin", a.getDocumentId()).getStatus());
        before = extractionCalls.get();
        workflow.action(kb, b.getDocumentId(), "admin", "run");
        advanceDocuments();
        assertTrue(indexer.runOnce());
        assertEquals(before, extractionCalls.get(), "Run resumes completed contributions instead of extracting again");
        advanceDocuments();
    }

    @Test
    void documentsAutomaticallyBuildActivatePauseResumeAndDelete() {
        bind("embed3");
        var first = documents.acceptText(
                kb, "admin", "auto-a.md", "# A\nAlice and Bob research knowledge graphs.", "markdown", 64, 16);
        workflow.action(kb, first.getDocumentId(), "admin", "pause");
        assertEquals("paused", documents.get(kb, "admin", first.getDocumentId()).getStatus());
        assertTrue(jobs.claim("paused-worker").isEmpty());

        var second = documents.acceptText(
                kb, "admin", "auto-b.md", "# B\nBob researches graphs and information.", "markdown", 64, 16);
        ingestion.process(jobs.claim("auto-test").orElseThrow());
        advanceDocuments();
        UUID generation = automaticGeneration();
        assertNotNull(generation);
        assertEquals(
                "indexing", documents.get(kb, "admin", second.getDocumentId()).getStatus());
        assertTrue(indexer.runOnce());
        advanceDocuments();
        assertEquals(
                "indexed", documents.get(kb, "admin", second.getDocumentId()).getStatus());
        assertEquals("paused", documents.get(kb, "admin", first.getDocumentId()).getStatus());

        workflow.action(kb, first.getDocumentId(), "admin", "run");
        var oldLease = jobs.claim("old-attempt").orElseThrow();
        workflow.action(kb, first.getDocumentId(), "admin", "pause");
        workflow.action(kb, first.getDocumentId(), "admin", "run");
        assertThrows(LeaseLostException.class, () -> jobs.load(oldLease));
        ingestion.process(jobs.claim("new-attempt").orElseThrow());
        advanceDocuments();
        UUID newer = automaticGeneration();
        assertNotEquals(generation, newer);
        assertTrue(indexer.runOnce());
        advanceDocuments();
        assertEquals(
                "indexed", documents.get(kb, "admin", first.getDocumentId()).getStatus());
        assertEquals(
                newer,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));

        workflow.action(kb, second.getDocumentId(), "admin", "delete");
        assertThrows(RagAccessException.class, () -> documents.content(kb, "admin", second.getDocumentId(), null));
        assertEquals(1, documents.list(kb, "admin", 0, 20).getTotal());
        assertNull(
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        advanceDocuments();
        UUID clean = automaticGeneration();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_document WHERE knowledge_base_id=? AND generation_id=? AND document_id=?",
                        Integer.class,
                        kb,
                        clean,
                        second.getDocumentId()));
        assertTrue(indexer.runOnce());
        advanceDocuments();
        assertEquals(
                clean,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        workflow.action(kb, first.getDocumentId(), "admin", "delete");
        advanceDocuments();
        assertEquals(0, documents.list(kb, "admin", 0, 20).getTotal());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_document_index_queue WHERE knowledge_base_id=?", Integer.class, kb));
    }

    @Test
    void documentRebuildFencesCancelledGenerationAndStaleCoordinatorErrors() {
        bind("embed3");
        var submission =
                documents.acceptText(kb, "admin", "rebuild.md", "Alice and Bob research graphs.", "markdown", 64, 16);
        ingestion.process(jobs.claim("workflow-test").orElseThrow());
        advanceDocuments();
        UUID original = automaticGeneration();
        var stale = indexes.claim().orElseThrow();
        long revision = jdbc.queryForObject(
                "SELECT revision FROM rag_document_index_queue WHERE knowledge_base_id=?", Long.class, kb);
        workflow.action(kb, submission.getDocumentId(), "admin", "pause");
        workflow.action(kb, submission.getDocumentId(), "admin", "run");
        workflow.failed(kb, revision, original, "stale_error");
        assertNull(documents.get(kb, "admin", submission.getDocumentId()).getErrorCode());
        assertThrows(RuntimeException.class, () -> indexes.check(stale));
        indexes.fail(stale, "late_failure");
        assertEquals("cancelled", state(original));
        advanceDocuments();
        assertNotEquals(original, automaticGeneration());
        assertTrue(indexer.runOnce());
        advanceDocuments();
        UUID active =
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb);
        workflow.action(kb, submission.getDocumentId(), "admin", "rebuild");
        advanceDocuments();
        UUID rebuilt = automaticGeneration();
        assertNotEquals(active, rebuilt);
        assertEquals(
                active,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        assertEquals("processed", jobs.get(kb, "admin", submission.getJobId()).getStatus());
    }

    @Test
    void writerCanAutomaticallyPublishButRevokedWriterCannotFinishAnotherBuild() {
        bind("embed3");
        jdbc.update("INSERT INTO app_user(username,password_hash) SELECT 'document-writer',password_hash"
                + " FROM app_user WHERE username='admin' ON CONFLICT (username) DO NOTHING");
        bases.grant(kb, "admin", "document-writer", KnowledgeRole.WRITER);
        var submission = documents.acceptText(
                kb, "document-writer", "writer.md", "Alice and Bob research knowledge graphs.", "markdown", 64, 16);
        ingestion.process(jobs.claim("writer-test").orElseThrow());
        advanceDocuments();
        assertTrue(indexer.runOnce());
        advanceDocuments();
        UUID active =
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb);
        assertNotNull(active);
        assertEquals(
                "indexed",
                documents.get(kb, "document-writer", submission.getDocumentId()).getStatus());
        workflow.action(kb, submission.getDocumentId(), "document-writer", "rebuild");
        advanceDocuments();
        var lease = indexes.claim().orElseThrow();
        bases.grant(kb, "admin", "document-writer", KnowledgeRole.READER);
        assertThrows(RagAccessException.class, () -> indexes.check(lease));
        assertThrows(
                RagAccessException.class,
                () -> workflow.action(kb, submission.getDocumentId(), "document-writer", "delete"));
        assertEquals(
                active,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    }

    @Test
    void secretIsEncryptedMaskedEditableAndAdminOnly() {
        var first = config("CHAT", "chat", 0);
        String serialized = json.writeValueAsString(first);
        assertFalse(serialized.contains("test-private-key"));
        UUID version = id(first);
        String cipher = jdbc.queryForObject(
                "SELECT secret_cipher FROM rag_model_configuration WHERE id=?", String.class, version);
        assertNotEquals("test-private-key", cipher);
        var update = request("CHAT", "changed", 0);
        update.setApiKey("");
        var second = models.save(UUID.fromString(first.get("profileId").toString()), update, "admin");
        assertEquals(version, id(second));
        assertFalse(second.containsKey("version"));
        assertEquals(true, second.get("keyConfigured"));
        assertThrows(RagAccessException.class, () -> models.list("unknown"));
        var invalid = request("CHAT", "bad", 0);
        invalid.setBaseUrl("file:///invalid-model-endpoint");
        assertThrows(IllegalArgumentException.class, () -> models.save(null, invalid, "admin"));
    }

    @Test
    void boundModelCannotBeDeletedUntilThisKnowledgeBaseUnbindsIt() {
        UUID model = id(config("CHAT", "delete-current", 0));
        models.bind(kb, Map.of("answer", model), "admin");
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class, () -> models.delete(model, "admin"));
        assertEquals(model, models.bindings(kb, "admin").get("answer"));
        models.bind(kb, Collections.singletonMap("answer", null), "admin");
        models.delete(model, "admin");
        assertThrows(RagAccessException.class, () -> models.version(model));
    }

    private long queueRevision() {
        return jdbc.queryForObject(
                "SELECT revision FROM rag_document_index_queue WHERE knowledge_base_id=?", Long.class, kb);
    }

    @Test
    void deletingCurrentModelDoesNotDeleteOrMutateCapturedIndexInputs() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        UUID snapshot = jdbc.queryForObject(
                "SELECT extract_version FROM rag_index_generation WHERE id=?", UUID.class, generation);
        var captured = models.version(snapshot);
        models.bind(
                kb,
                new HashMap<>(Map.of(
                        "extract",
                        id(config("CHAT", "replacement", 0)),
                        "summary",
                        id(config("CHAT", "replacement-summary", 0)))),
                "admin");
        models.delete(captured.getProfileId(), "admin");
        assertEquals(captured.getModel(), models.version(snapshot).getModel());
        assertEquals(
                generation,
                jdbc.queryForObject("SELECT id FROM rag_index_generation WHERE id=?", UUID.class, generation));
    }

    @Test
    void streamedObjectsPersistBeforeCompletionButOnlySuccessfulStreamsCompleteChunks() throws Exception {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        holdExtraction.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(indexer::runOnce);
            try {
                assertTrue(extractionSent.await(5, TimeUnit.SECONDS));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=?"
                                                + " AND NOT completed AND extraction->'entities'->0->>'name'='Alice'",
                                        Integer.class,
                                        generation)
                                == 0
                        && System.nanoTime() < until) Thread.sleep(20);
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=?"
                                        + " AND NOT completed AND extraction->'entities'->0->>'name'='Alice'",
                                Integer.class,
                                generation));
                assertFalse(future.isDone());
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rag_index_chunk_vector WHERE generation_id=?",
                                Integer.class,
                                generation));
            } finally {
                extractionRelease.countDown();
            }
            assertTrue(future.get(10, TimeUnit.SECONDS));
        }
        assertEquals("ready", state(generation));
        assertEquals(
                "研究员",
                jdbc.queryForObject(
                        "SELECT attributes->'职业'->0->>'value' FROM rag_index_entity WHERE generation_id=? AND name='Alice'",
                        String.class,
                        generation));
        assertEquals(
                "合作",
                jdbc.queryForObject(
                        "SELECT relation_types->>0 FROM rag_index_relation WHERE generation_id=?",
                        String.class,
                        generation));
        var graph = indexes.graph(kb, generation, "admin");
        @SuppressWarnings("unchecked")
        var entities = (List<Map<String, Object>>) graph.get("entities");
        var alice = entities.stream()
                .filter(e -> e.get("name").equals("Alice"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "研究员",
                json.readTree(alice.get("attributes").toString())
                        .path("职业")
                        .get(0)
                        .path("value")
                        .asText());
    }

    @Test
    void oldExtractionProtocolCannotMixItsCheckpointsIntoANewFormat() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        jdbc.update("UPDATE rag_index_generation SET prompt_version='lightrag-aaebfe1-text-v1' WHERE id=?", generation);
        assertTrue(indexer.runOnce());
        assertEquals("failed", state(generation));
        assertEquals(
                "index_version_unsupported",
                jdbc.queryForObject(
                        "SELECT error_code FROM rag_index_generation WHERE id=?", String.class, generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND extraction IS NOT NULL",
                        Integer.class,
                        generation));
    }

    @Test
    void pausingAnIdleExtractionCancelsTheModelSubscriptionWithoutPublishing() throws Exception {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        jdbc.update("UPDATE rag_index_generation SET automatic=TRUE WHERE id=?", generation);
        jdbc.update("UPDATE rag_document_index_queue SET generation_id=? WHERE knowledge_base_id=?", generation, kb);
        UUID document = jdbc.queryForObject("SELECT id FROM rag_document WHERE knowledge_base_id=?", UUID.class, kb);
        holdExtraction.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(indexer::runOnce);
            try {
                assertTrue(extractionSent.await(5, TimeUnit.SECONDS));
                workflow.action(kb, document, "admin", "pause");
                assertTrue(future.get(3, TimeUnit.SECONDS));
            } finally {
                extractionRelease.countDown();
            }
        }
        assertEquals("cancelled", state(generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
                        Integer.class,
                        generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, generation));
    }

    @Test
    void completeJsonWithoutProviderSuccessCannotPublishIndexOrChunkVectors() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        truncateExtraction.set(true);
        assertTrue(indexer.runOnce());
        assertEquals("failed", state(generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
                        Integer.class,
                        generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk_vector WHERE generation_id=?",
                        Integer.class,
                        generation));
        assertNull(
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retriesMissingEndpointsOnceThenKeepsRepairedOrDropsUnresolvedRelations(boolean repaired) {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        jdbc.update("UPDATE rag_index_generation SET gleaning=0 WHERE id=?", generation);
        orphanRelations.set(true);
        repairEndpoints.set(repaired);
        extractionCalls.set(0);
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(generation));
        int chunks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=?", Integer.class, generation);
        assertEquals(chunks + 1, extractionCalls.get());
        assertEquals(
                repaired ? 1 : 0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id=?", Integer.class, generation));
        assertTrue(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, generation)
                > 0);
    }

    @Test
    void typedEntityIdentityAndNativeAttributeValuesPersistWithChunkSources() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        typedEntities.set(true);
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(generation));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=? AND name='长安'",
                        Integer.class,
                        generation));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=? AND attributes->'人口'->0->>'type'='integer' AND attributes->'人口'->0->'value'='35'::JSONB AND JSONB_ARRAY_LENGTH(source_ids)>0",
                        Integer.class,
                        generation));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id=? AND source_id<>target_id AND JSONB_ARRAY_LENGTH(source_ids)>0",
                        Integer.class,
                        generation));
    }

    @Test
    void incompleteSchemaCorrectionCannotPublishIndex() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        invalidEntityAt.set(1);
        truncateCorrection.set(true);
        assertTrue(indexer.runOnce());
        assertEquals(2, extractionCalls.get());
        assertEquals("failed", state(generation));
        assertEquals(
                "model_stream_incomplete",
                jdbc.queryForObject(
                        "SELECT error_code FROM rag_index_generation WHERE id=?", String.class, generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk_vector WHERE generation_id=?",
                        Integer.class,
                        generation));
        assertNull(
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    }

    @Test
    void repeatedMissingNameFailsWithSpecificCodeAndCannotPublishPartialIndex() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        invalidEntityAt.set(1);
        repeatInvalidEntity.set(true);
        assertTrue(indexer.runOnce());
        assertEquals(2, extractionCalls.get());
        assertEquals("failed", state(generation));
        assertEquals(
                "extraction_invalid_field_name",
                jdbc.queryForObject(
                        "SELECT error_code FROM rag_index_generation WHERE id=?", String.class, generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
                        Integer.class,
                        generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk_vector WHERE generation_id=?",
                        Integer.class,
                        generation));
        assertNull(
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void missingNameInInitialOrGleaningOutputIsCorrectedOnce(int invalidCall) {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        invalidEntityAt.set(invalidCall);
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(generation));
        assertEquals(3, extractionCalls.get());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=?", Integer.class, generation));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id=?", Integer.class, generation));
    }

    @Test
    void omittedAttributesDoNotFailIndexOrLoseRelations() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        omitAttributes.set(true);
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(generation));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_entity WHERE generation_id=? AND attributes='{}'::JSONB",
                        Integer.class,
                        generation));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_relation WHERE generation_id=?", Integer.class, generation));
    }

    @Test
    void incompleteCorrectionCannotPublishChunkVectorsOrIndex() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        orphanRelations.set(true);
        repairEndpoints.set(true);
        truncateCorrection.set(true);
        assertTrue(indexer.runOnce());
        assertEquals(2, extractionCalls.get());
        assertEquals("failed", state(generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk WHERE generation_id=? AND completed",
                        Integer.class,
                        generation));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_chunk_vector WHERE generation_id=?",
                        Integer.class,
                        generation));
        assertNull(
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
    }

    @Test
    void editedExtensionParametersApplyToProbesAndNewExtraction() {
        bind("embed3");
        var request = request("CHAT", "extensions", 0);
        var extensions = Map.<String, Object>of("chat_template_kwargs", Map.of("enable_thinking", false), "top_p", 0.8);
        request.setExtraBody(extensions);
        var first = models.save(null, request, "admin");
        UUID old = id(first), profile = models.version(old).getProfileId();
        assertEquals(extensions, first.get("extraBody"));
        assertEquals(extensions, models.version(old).getExtraBody());
        request.setExtraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", true)));
        models.save(profile, request, "admin");
        assertEquals(
                Map.of("chat_template_kwargs", Map.of("enable_thinking", true)),
                models.version(old).getExtraBody());
        assertEquals(true, models.test(old, "admin").get("success"));
        assertTrue(lastChatRequest
                .get()
                .path("chat_template_kwargs")
                .path("enable_thinking")
                .asBoolean(false));
        request.setExtraBody(extensions);
        models.save(profile, request, "admin");
        models.bind(kb, Map.of("extract", old, "summary", old), "admin");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        assertTrue(indexer.runOnce());
        assertEquals("ready", state(generation));
        assertFalse(lastChatRequest
                .get()
                .path("chat_template_kwargs")
                .path("enable_thinking")
                .asBoolean(true));
        var embedding = request("EMBEDDING", "embed3", 3);
        embedding.setExtraBody(extensions);
        assertThrows(IllegalArgumentException.class, () -> models.save(null, embedding, "admin"));
    }

    @Test
    void connectionTestsExerciseAllThreeProtocols() {
        for (String kind : List.of("CHAT", "EMBEDDING", "RERANK")) {
            var m = config(kind, kind.equals("EMBEDDING") ? "embed3" : "chat", kind.equals("EMBEDDING") ? 3 : 0);
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
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
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
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
        indexes.activate(kb, second, first, "admin");
        assertEquals(
                4,
                jdbc.queryForObject("SELECT dimensions FROM rag_index_generation WHERE id=?", Integer.class, second));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT vector_dims(embedding) FROM rag_index_chunk_vector WHERE generation_id=? LIMIT" + " 1",
                        Integer.class,
                        first));
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT vector_dims(embedding) FROM rag_index_chunk_vector WHERE generation_id=? LIMIT" + " 1",
                        Integer.class,
                        second));
        assertThrows(RagAccessException.class, () -> indexes.activate(kb, first, first, "admin"));
        indexes.activate(kb, first, second, "admin");
        assertEquals(
                first,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
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
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
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
                "UPDATE rag_index_generation SET lease_until=CLOCK_TIMESTAMP()-INTERVAL '1 second' WHERE" + " id=?",
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
                jdbc.queryForObject("SELECT weight FROM rag_index_relation WHERE generation_id=?", Double.class, gen));
        assertThrows(
                RuntimeException.class,
                () -> jdbc.update(
                        "UPDATE rag_index_chunk_vector SET embedding='[1,2,3,4]'::vector WHERE" + " generation_id=?",
                        gen));
        assertThrows(
                RuntimeException.class,
                () -> jdbc.update(
                        "UPDATE rag_index_model_snapshot SET model='tampered' WHERE id=(SELECT embedding_version"
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
                jdbc.queryForObject("SELECT embedding_version FROM rag_index_generation WHERE id=?", UUID.class, a);
        bind("other-embed3");
        UUID b = id(indexes.create(kb, "admin"));
        indexer.runOnce();
        assertNotEquals(
                oldModel,
                jdbc.queryForObject("SELECT embedding_version FROM rag_index_generation WHERE id=?", UUID.class, b));
        assertEquals(
                a,
                jdbc.queryForObject("SELECT active_generation_id FROM rag_knowledge_base WHERE id=?", UUID.class, kb));
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
        jdbc.update("INSERT INTO app_user(username,password_hash) SELECT 'model-reader',password_hash FROM"
                + " app_user WHERE username='admin' ON CONFLICT (username) DO NOTHING");
        bases.grant(kb, "admin", "model-reader", KnowledgeRole.READER);
        var client = java.net.http.HttpClient.newBuilder()
                .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
                .build();
        String root = "http://localhost:" + port;
        var get = java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models"))
                .GET()
                .build();
        assertEquals(
                401,
                client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        String csrf = json.readTree(client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .body())
                .path("token")
                .asText();
        assertEquals(
                200,
                client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/login"))
                                        .header("X-CSRF-TOKEN", csrf)
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                                "username=model-reader&password=123456"))
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        csrf = json.readTree(client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .body())
                .path("token")
                .asText();
        assertEquals(
                403,
                client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        assertEquals(
                403,
                client.send(
                                java.net.http.HttpRequest.newBuilder(
                                                URI.create(root + "/api/knowledge-bases/" + kb + "/indexes"))
                                        .header("X-CSRF-TOKEN", csrf)
                                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        assertEquals(
                403,
                client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models"))
                                        .header("Content-Type", "application/json")
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        assertEquals(
                403,
                client.send(
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
        var client = java.net.http.HttpClient.newBuilder()
                .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
                .build();
        String root = "http://localhost:" + port;
        String csrf = json.readTree(client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .body())
                .path("token")
                .asText();
        assertEquals(
                200,
                client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/login"))
                                        .header("X-CSRF-TOKEN", csrf)
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                                "username=admin&password=123456"))
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());
        csrf = json.readTree(client.send(
                                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/auth/csrf"))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .body())
                .path("token")
                .asText();
        var result = client.send(
                java.net.http.HttpRequest.newBuilder(
                                URI.create(root + "/api/knowledge-bases/" + kb + "/model-bindings"))
                        .header("X-CSRF-TOKEN", csrf)
                        .header("Content-Type", "application/json")
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(204, result.statusCode());
        assertEquals("", result.body());
        var models = client.send(
                java.net.http.HttpRequest.newBuilder(URI.create(root + "/api/admin/models"))
                        .GET()
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, models.statusCode());
        assertFalse(models.body().contains("test-private-key"));
        assertFalse(models.body().contains("secret_cipher"));
    }

    @Test
    void editingUpdatesOneConfigurationWithoutSavingHistory() {
        var created = config("CHAT", "original", 0);
        UUID model = id(created);
        var edited = models.save(model, request("CHAT", "edited", 0), "admin");
        assertEquals(model, id(edited));
        assertFalse(edited.containsKey("version"));
        assertEquals("edited", models.version(model).getModel());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM rag_model_configuration WHERE id=?", Integer.class, model));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_index_model_snapshot WHERE profile_id=?", Integer.class, model));
    }

    @Test
    void bindingsAreKnowledgeBaseScopedAndOwnerControlled() {
        UUID other = bases.create("admin", "other model scope", "").getId();
        assertTrue(models.bindings(other, "admin").isEmpty());
        UUID a = id(config("CHAT", "scope-a", 0)), b = id(config("CHAT", "scope-b", 0));
        models.bind(kb, Map.of("answer", a), "admin");
        models.bind(other, Map.of("answer", b), "admin");
        assertEquals(a, models.snapshot(kb).get("answer"));
        assertEquals(b, models.snapshot(other).get("answer"));
        jdbc.update("INSERT INTO app_user(username,password_hash) SELECT 'binding-reader',password_hash FROM app_user"
                + " WHERE username='admin' ON CONFLICT (username) DO NOTHING");
        bases.grant(kb, "admin", "binding-reader", KnowledgeRole.READER);
        var options = models.options(kb, "binding-reader");
        assertFalse(options.isEmpty());
        assertTrue(options.stream().allMatch(option -> option.keySet().equals(Set.of("id", "name", "kind"))));
        assertEquals(
                403,
                assertThrows(RagAccessException.class, () -> models.bind(kb, Map.of("answer", b), "binding-reader"))
                        .getStatus());
        assertThrows(RagAccessException.class, () -> models.bindings(other, "binding-reader"));
        assertEquals(a, models.snapshot(kb).get("answer"));
        UUID owned = bases.create("binding-reader", "non-admin owner", "").getId();
        models.bind(owned, Map.of("answer", b), "binding-reader");
        assertEquals(b, models.bindings(owned, "binding-reader").get("answer"));
        assertEquals(
                403,
                assertThrows(RagAccessException.class, () -> models.list("binding-reader"))
                        .getStatus());
    }

    @Test
    void indexSnapshotsSurviveEditingAndDeletingUnboundConfiguration() {
        bind("embed3");
        ingest();
        UUID generation = id(indexes.create(kb, "admin"));
        UUID snapshot = jdbc.queryForObject(
                "SELECT embedding_version FROM rag_index_generation WHERE id=?", UUID.class, generation);
        var old = models.version(snapshot);
        UUID model = old.getProfileId();
        models.save(model, request("EMBEDDING", "embed4", 4), "admin");
        assertEquals(3, models.version(snapshot).getDimensions());
        assertEquals(4, models.version(model).getDimensions());
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class, () -> models.delete(model, "admin"));
        models.bind(kb, Collections.singletonMap("embedding", null), "admin");
        models.delete(model, "admin");
        assertThrows(RagAccessException.class, () -> models.version(model));
        assertEquals(3, models.version(snapshot).getDimensions());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_generation WHERE id=?", Integer.class, generation));
    }
}
