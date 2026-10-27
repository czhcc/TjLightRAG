package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.*;
import com.trs.app.rag.ingestion.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL ingestion durability, tenant and transaction tests.
 *
 * @author agent
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"rag.ingestion.enabled=false", "rag.indexing.enabled=false"})
class IngestionIntegrationTest {
  @Container
  static final GenericContainer<?> DB =
      new GenericContainer<>(VectorStorageIntegrationTest.IMAGE)
          .withEnv("POSTGRES_DB", "ingestion")
          .withEnv("POSTGRES_USER", "ingestion")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> url());
    r.add("spring.datasource.username", () -> "ingestion");
    r.add("spring.datasource.password", () -> "test-only");
    r.add("spring.flyway.url", () -> url());
    r.add("spring.flyway.user", () -> "ingestion");
    r.add("spring.flyway.password", () -> "test-only");
    r.add("rag.vector.dimensions", () -> "3");
    r.add("rag.vector.model", () -> "fixture-3d");
  }

  static String url() {
    return "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/ingestion";
  }

  @Autowired KnowledgeBaseService bases;
  @Autowired DocumentService documents;
  @Autowired JobService jobs;
  @Autowired IngestionProcessor processor;
  @Autowired FixedTokenChunker chunker;
  @Autowired JdbcTemplate jdbc;
  @Autowired RagObjectStore objectStore;
  @LocalServerPort int port;
  UUID kb;

  @BeforeEach
  void prepare() {
    kb = bases.create("admin", "documents", "").getId();
    jdbc.update(
        "INSERT INTO app_user(username,password_hash) VALUES ('reader','test'),('outsider','test')"
            + " ON CONFLICT DO NOTHING");
    bases.grant(kb, "admin", "reader", KnowledgeRole.READER);
  }

  @AfterEach
  void cleanup() {
    bases.delete(kb, "admin");
  }

  Submission text(String source, String text) {
    return documents.acceptText(kb, "admin", source, text, "markdown", 32, 8);
  }

  void run(Submission submission) {
    var lease = jobs.claim("test-worker").orElseThrow();
    assertEquals(submission.getJobId(), lease.getId());
    processor.process(lease);
  }

  @Test
  void persistsOriginalChunksAndSharedSourcesWithoutDuplicateJobs() {
    String input = "# Heading\n\n知识图谱🙂 retrieval. ".repeat(12);
    var first = text("first.md", input);
    assertFalse(first.isDuplicate());
    assertEquals("pending", jobs.get(kb, "admin", first.getJobId()).getStatus());
    assertEquals(0, documents.chunks(kb, "admin", first.getDocumentId(), null, 0, 100).getTotal());
    run(first);
    assertEquals("processed", jobs.get(kb, "admin", first.getJobId()).getStatus());
    assertArrayEquals(
        input.getBytes(StandardCharsets.UTF_8),
        documents.original(kb, "reader", first.getDocumentId(), null));
    assertEquals(input, documents.content(kb, "reader", first.getDocumentId(), null));
    var chunks = documents.chunks(kb, "reader", first.getDocumentId(), null, 0, 100);
    assertTrue(chunks.getTotal() > 1);
    assertTrue(chunks.getItems().stream().anyMatch(c -> !c.getHeadings().isEmpty()));
    var duplicate = text("first.md", input);
    assertTrue(duplicate.isDuplicate());
    assertEquals(first.getJobId(), duplicate.getJobId());
    var second = text("second.md", input);
    run(second);
    assertNotEquals(first.getDocumentId(), second.getDocumentId());
    var secondChunks = documents.chunks(kb, "reader", second.getDocumentId(), null, 0, 100);
    assertEquals(
        chunks.getItems().stream().map(ChunkView::getId).toList(),
        secondChunks.getItems().stream().map(ChunkView::getId).toList());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_ingestion_job WHERE revision_id=?",
            Integer.class,
            first.getRevisionId()));
    assertThrows(RagAccessException.class, () -> documents.list(kb, "outsider", 0, 20));
    assertThrows(
        RagAccessException.class,
        () -> documents.acceptText(kb, "reader", "no.txt", "no", "text", 32, 8));
    assertThrows(
        RagAccessException.class,
        () -> documents.original(kb, "outsider", first.getDocumentId(), null));
  }

  @Test
  void concurrentSubmissionIsIdempotentAndRevisionsStayImmutable() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var tasks =
          pool.invokeAll(
              List.<Callable<Submission>>of(
                  () -> text("same.md", "same body"), () -> text("same.md", "same body")));
      assertEquals(tasks.get(0).get().getJobId(), tasks.get(1).get().getJobId());
    }
    var first = text("same.md", "same body");
    run(first);
    var changed = text("same.md", "changed body");
    run(changed);
    assertEquals(first.getDocumentId(), changed.getDocumentId());
    assertNotEquals(first.getRevisionId(), changed.getRevisionId());
    assertEquals(
        "same body", documents.content(kb, "admin", first.getDocumentId(), first.getRevisionId()));
    assertEquals("changed body", documents.content(kb, "admin", first.getDocumentId(), null));
  }

  @Test
  void expiredLeaseResumesParsedCheckpointAndFencesOldWorker() {
    var submission = text("restart.md", "知识图谱🙂 ".repeat(100));
    var old = jobs.claim("before-restart").orElseThrow();
    jobs.checkpoint(old, "知识图谱🙂 ".repeat(100));
    jdbc.update(
        "UPDATE rag_ingestion_job SET lease_until=CLOCK_TIMESTAMP()-INTERVAL '1 second' WHERE id=?",
        old.getId());
    var replacement = jobs.claim("after-restart").orElseThrow();
    assertNotEquals(old.getToken(), replacement.getToken());
    assertThrows(LeaseLostException.class, () -> jobs.complete(old, List.of()));
    assertFalse(jobs.heartbeat(old));
    processor.process(replacement);
    assertEquals("processed", jobs.get(kb, "admin", submission.getJobId()).getStatus());
    assertEquals(2, jobs.get(kb, "admin", submission.getJobId()).getAttempts());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_job_event WHERE job_id=? AND event='published'",
            Integer.class,
            submission.getJobId()));
  }

  @Test
  void cancellationRetryAndPermanentParseFailureAreDurable() {
    var pending = text("cancel.md", "cancel me");
    jobs.cancel(kb, "admin", pending.getJobId());
    assertEquals("cancelled", jobs.get(kb, "admin", pending.getJobId()).getStatus());
    assertTrue(jobs.claim("worker").isEmpty());
    jobs.retry(kb, "admin", pending.getJobId());
    var lease = jobs.claim("worker").orElseThrow();
    jobs.cancel(kb, "admin", pending.getJobId());
    processor.process(lease);
    assertEquals("cancelled", jobs.get(kb, "admin", pending.getJobId()).getStatus());
    assertEquals(0, documents.chunks(kb, "admin", pending.getDocumentId(), null, 0, 20).getTotal());
    jobs.retry(kb, "admin", pending.getJobId());
    run(pending);
    assertEquals("processed", jobs.get(kb, "admin", pending.getJobId()).getStatus());
    var invalid =
        documents.acceptUpload(kb, "admin", "bad.txt", new byte[] {(byte) 0xC3, 0x28}, 32, 8);
    run(invalid);
    assertEquals("failed", jobs.get(kb, "admin", invalid.getJobId()).getStatus());
    assertEquals("invalid_encoding", jobs.get(kb, "admin", invalid.getJobId()).getErrorCode());
    assertTrue(jobs.claim("worker").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> text("empty.md", " \n"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            documents.acceptUpload(
                kb, "admin", "file.pdf", "%PDF-".getBytes(StandardCharsets.UTF_8), 32, 8));
  }

  @Test
  void publicationRollsBackAllChunksAndRevokedWriterCannotPublish() {
    var submission = text("atomic.md", "atomic document🙂 ".repeat(20));
    var lease = jobs.claim("worker").orElseThrow();
    jobs.checkpoint(lease, "atomic document🙂 ".repeat(20));
    var pieces = chunker.split("atomic document🙂 ".repeat(20), 32, 8, 10000, () -> false);
    pieces.getLast().setTokens(-1);
    assertThrows(
        org.springframework.dao.DataAccessException.class, () -> jobs.complete(lease, pieces));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_document_chunk WHERE revision_id=?",
            Integer.class,
            submission.getRevisionId()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_chunk WHERE knowledge_base_id=?", Integer.class, kb));
    jobs.fail(lease, "processing_error", true);
    jdbc.update(
        "UPDATE rag_ingestion_job SET next_attempt_at=CLOCK_TIMESTAMP()-INTERVAL '1 second' WHERE"
            + " id=?",
        lease.getId());
    processor.process(jobs.claim("replacement").orElseThrow());
    assertEquals("processed", jobs.get(kb, "admin", submission.getJobId()).getStatus());
    bases.grant(kb, "admin", "reader", KnowledgeRole.WRITER);
    var other = documents.acceptText(kb, "reader", "revoked.txt", "not publishable", "text", 32, 8);
    var revoked = jobs.claim("worker").orElseThrow();
    bases.revoke(kb, "admin", "reader");
    processor.process(revoked);
    assertEquals("failed", jobs.get(kb, "admin", other.getJobId()).getStatus());
    assertEquals("permission_lost", jobs.get(kb, "admin", other.getJobId()).getErrorCode());
  }

  @Test
  void boundedAttemptsAndForeignKeysProtectOtherKnowledgeBases() {
    var submission = text("exhaust.md", "bounded work");
    for (int i = 0; i < 3; i++) {
      var lease = jobs.claim("crashed-" + i).orElseThrow();
      jdbc.update(
          "UPDATE rag_ingestion_job SET lease_until=CLOCK_TIMESTAMP()-INTERVAL '1 second' WHERE"
              + " id=?",
          lease.getId());
    }
    assertTrue(jobs.claim("after-exhaustion").isEmpty());
    assertEquals("failed", jobs.get(kb, "admin", submission.getJobId()).getStatus());
    assertEquals("attempts_exhausted", jobs.get(kb, "admin", submission.getJobId()).getErrorCode());
    assertThrows(RagAccessException.class, () -> jobs.retry(kb, "reader", submission.getJobId()));
    UUID other = bases.create("admin", "other", "").getId();
    try {
      assertThrows(
          RagAccessException.class,
          () -> documents.get(other, "admin", submission.getDocumentId()));
      assertThrows(RagAccessException.class, () -> jobs.get(other, "admin", submission.getJobId()));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              jdbc.update(
                  "INSERT INTO rag_document_blob (knowledge_base_id,revision_id,original) VALUES"
                      + " (?,?,?)",
                  other,
                  submission.getRevisionId(),
                  new byte[] {1}));
    } finally {
      bases.delete(other, "admin");
    }
  }

  @Test
  void httpSubmissionMultipartAndLimitsUseTheSessionBoundary() throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    String base = "http://localhost:" + port;
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(
                        URI.create(base + "/api/knowledge-bases/" + kb + "/documents"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    String token =
        json.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=123456"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    token =
        json.readTree(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(),
                        HttpResponse.BodyHandlers.ofString())
                    .body())
            .path("token")
            .asText();
    String prefix = base + "/api/knowledge-bases/" + kb;
    String payload =
        json.writeValueAsString(
            Map.of(
                "sourceName",
                "http.md",
                "text",
                "# Header\n\n正文🙂",
                "format",
                "markdown",
                "chunkSize",
                32,
                "overlap",
                8));
    assertEquals(
        403,
        client
            .send(
                HttpRequest.newBuilder(URI.create(prefix + "/documents/text"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(prefix + "/documents/text"))
                .header("X-CSRF-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, response.statusCode());
    UUID job = UUID.fromString(json.readTree(response.body()).path("jobId").asText());
    var detail =
        client.send(
            HttpRequest.newBuilder(URI.create(prefix + "/jobs/" + job)).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, detail.statusCode());
    assertEquals("pending", json.readTree(detail.body()).path("status").asText());
    assertFalse(detail.body().contains("leaseToken"));
    assertEquals(
        202,
        client
            .send(
                HttpRequest.newBuilder(URI.create(prefix + "/jobs/" + job + "/cancel"))
                    .header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    String boundary = "stage2-boundary";
    var upload =
        client.send(
            HttpRequest.newBuilder(URI.create(prefix + "/documents/upload?chunkSize=32&overlap=8"))
                .header("X-CSRF-TOKEN", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        multipart(
                            boundary,
                            "upload.md",
                            "# File\n知识图谱🙂".getBytes(StandardCharsets.UTF_8))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, upload.statusCode());
    processor.process(jobs.claim("http-worker").orElseThrow());
    UUID document = UUID.fromString(json.readTree(upload.body()).path("documentId").asText());
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(prefix + "/documents/" + document + "/original"))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .statusCode());
    assertTrue(
        client
            .send(
                HttpRequest.newBuilder(URI.create(prefix + "/documents/" + document + "/content"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .body()
            .contains("知识图谱🙂"));
    var fill = new byte[DocumentService.MAX_BYTES + 1];
    Arrays.fill(fill, (byte) 'a');
    assertEquals(
        413,
        client
            .send(
                HttpRequest.newBuilder(URI.create(prefix + "/documents/upload"))
                    .header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(
                        HttpRequest.BodyPublishers.ofByteArray(
                            multipart(boundary, "huge.txt", fill)))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  private byte[] multipart(String boundary, String filename, byte[] input) throws Exception {
    var output = new ByteArrayOutputStream();
    output.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\nContent-Type: text/plain\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    output.write(input);
    output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return output.toByteArray();
  }

  @Test
  void competingWorkersClaimDifferentJobsAndPublishSharedBlocks() throws Exception {
    for (int round = 0; round < 4; round++) {
      var first =
          text("worker-one-" + round + ".md", ("shared content🙂 " + round + " ").repeat(40));
      var second =
          text("worker-two-" + round + ".md", ("shared content🙂 " + round + " ").repeat(40));
      try (var pool = Executors.newFixedThreadPool(2)) {
        var claimed =
            pool.invokeAll(
                List.<Callable<JobLease>>of(
                    () -> jobs.claim("one").orElseThrow(), () -> jobs.claim("two").orElseThrow()));
        var a = claimed.get(0).get();
        var b = claimed.get(1).get();
        assertNotEquals(a.getId(), b.getId());
        var published =
            pool.invokeAll(
                List.<Callable<Boolean>>of(
                    () -> {
                      processor.process(a);
                      return true;
                    },
                    () -> {
                      processor.process(b);
                      return true;
                    }));
        for (var outcome : published) assertTrue(outcome.get());
      }
      assertEquals("processed", jobs.get(kb, "admin", first.getJobId()).getStatus());
      assertEquals("processed", jobs.get(kb, "admin", second.getJobId()).getStatus());
      var a = documents.chunks(kb, "admin", first.getDocumentId(), null, 0, 100);
      var b = documents.chunks(kb, "admin", second.getDocumentId(), null, 0, 100);
      assertEquals(
          a.getItems().stream().map(ChunkView::getId).toList(),
          b.getItems().stream().map(ChunkView::getId).toList());
    }
  }

  @Test
  void versionedInputAndSourceIntegrityAreNotSilentlyChanged() {
    var unknown = text("version.md", "versioned document");
    jdbc.update(
        "UPDATE rag_document_revision SET tokenizer='unknown-tokenizer' WHERE knowledge_base_id=?"
            + " AND id=?",
        kb,
        unknown.getRevisionId());
    run(unknown);
    assertEquals("failed", jobs.get(kb, "admin", unknown.getJobId()).getStatus());
    assertEquals("unsupported_version", jobs.get(kb, "admin", unknown.getJobId()).getErrorCode());
    var corrupt = text("corrupt.md", "original content");
    jdbc.update(
        "UPDATE rag_document_blob SET original=? WHERE knowledge_base_id=? AND revision_id=?",
        "changed bytes".getBytes(StandardCharsets.UTF_8),
        kb,
        corrupt.getRevisionId());
    run(corrupt);
    assertEquals("source_integrity", jobs.get(kb, "admin", corrupt.getJobId()).getErrorCode());
    var valid = text("immutable.md", "unchanged content");
    run(valid);
    var chunk =
        documents.chunks(kb, "admin", valid.getDocumentId(), null, 0, 20).getItems().getFirst();
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> objectStore.putChunk(kb, "admin", chunk.getId(), "mutated content"));
    assertEquals("unchanged content", documents.content(kb, "admin", valid.getDocumentId(), null));
  }
}
