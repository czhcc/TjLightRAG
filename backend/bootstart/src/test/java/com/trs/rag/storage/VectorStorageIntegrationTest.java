package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 18 + pgvector contract, authorization and real HTTP tests.
 *
 * @author agent
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VectorStorageIntegrationTest {
  static final String IMAGE =
      "pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0";

  @Container
  static final GenericContainer<?> DB =
      new GenericContainer<>(IMAGE)
          .withEnv("POSTGRES_DB", "ragtest")
          .withEnv("POSTGRES_USER", "ragtest")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/ragtest");
    r.add("spring.datasource.username", () -> "ragtest");
    r.add("spring.datasource.password", () -> "test-only");
    r.add(
        "spring.flyway.url",
        () -> "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/ragtest");
    r.add("spring.flyway.user", () -> "ragtest");
    r.add("spring.flyway.password", () -> "test-only");
    r.add("rag.vector.dimensions", () -> "3");
    r.add("rag.vector.model", () -> "fixture-3d");
  }

  @Autowired KnowledgeBaseService knowledgeBases;
  @Autowired RagObjectStore objects;
  @Autowired PgVectorStore vectors;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  @Autowired PasswordEncoder passwords;
  @LocalServerPort int port;
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void users() {
    for (String name : List.of("reader", "writer", "outsider"))
      jdbc.update(
          "INSERT INTO app_user (username, password_hash) VALUES (?, ?) ON CONFLICT (username) DO"
              + " NOTHING",
          name,
          passwords.encode("test-pass"));
  }

  @ParameterizedTest
  @EnumSource(VectorKind.class)
  void exactCosineRanksAllThreeFamiliesAndDoesNotLeakOtherKnowledgeBases(VectorKind kind) {
    UUID kb = knowledgeBases.create("admin", "vectors", "").getId();
    UUID other = knowledgeBases.create("admin", "other", "").getId();
    UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
    for (UUID id : List.of(first, second, third)) seed(kb, kind, id);
    seed(other, kind, first);
    vectors.upsert(kb, "admin", kind, first, new float[] {1, 0, 0});
    vectors.upsert(kb, "admin", kind, second, new float[] {.8f, .6f, 0});
    vectors.upsert(kb, "admin", kind, third, new float[] {0, 1, 0});
    vectors.upsert(other, "admin", kind, first, new float[] {1, 0, 0});
    var hits = vectors.search(kb, "admin", kind, new float[] {1, 0, 0}, 2, .7);
    assertEquals(List.of(first, second), hits.stream().map(VectorHit::getObjectId).toList());
    assertEquals(1, hits.get(0).getSimilarity(), 1e-6);
    assertEquals(.8, hits.get(1).getSimilarity(), 1e-6);
    vectors.upsert(kb, "admin", kind, second, new float[] {0, 1, 0});
    assertEquals(1, vectors.search(kb, "admin", kind, new float[] {1, 0, 0}, 10, .7).size());
    assertThrows(
        RagAccessException.class,
        () -> vectors.search(kb, "outsider", kind, new float[] {1, 0, 0}, 2, 0));
  }

  @Test
  void validatesVectorInputsAndDatabaseRejectsWrongDimensionsAndZeroNorm() {
    UUID kb = knowledgeBases.create("admin", "validation", "").getId(), id = UUID.randomUUID();
    objects.putChunk(kb, "admin", id, "text");
    for (float[] invalid :
        List.of(
            new float[] {1, 0},
            new float[] {0, 0, 0},
            new float[] {Float.NaN, 0, 1},
            new float[] {Float.POSITIVE_INFINITY, 0, 1}))
      assertThrows(
          IllegalArgumentException.class,
          () -> vectors.upsert(kb, "admin", VectorKind.CHUNK, id, invalid));
    assertThrows(
        IllegalArgumentException.class,
        () -> vectors.search(kb, "admin", VectorKind.CHUNK, new float[] {1, 0, 0}, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> vectors.search(kb, "admin", VectorKind.CHUNK, new float[] {1, 0, 0}, 101, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> vectors.search(kb, "admin", VectorKind.CHUNK, new float[] {1, 0, 0}, 2, Double.NaN));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO rag_chunk_embedding (knowledge_base_id,object_id,embedding) VALUES"
                    + " (?,?,CAST(? AS vector))",
                kb,
                id,
                "[1,0]"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO rag_chunk_embedding (knowledge_base_id,object_id,embedding) VALUES"
                    + " (?,?,CAST(? AS vector))",
                kb,
                id,
                "[0,0,0]"));
  }

  @Test
  void foreignKeysPreventCrossKnowledgeBaseLinksAndDuplicates() {
    UUID a = knowledgeBases.create("admin", "A", "").getId(),
        b = knowledgeBases.create("admin", "B", "").getId();
    UUID id = UUID.randomUUID();
    objects.putChunk(b, "admin", id, "private");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO rag_chunk_embedding (knowledge_base_id,object_id,embedding) VALUES"
                    + " (?,?,CAST(? AS vector))",
                a,
                id,
                "[1,0,0]"));
    UUID x = UUID.randomUUID(), y = UUID.randomUUID();
    objects.putEntity(a, "admin", x, "Alice", "person", "");
    objects.putEntity(b, "admin", y, "Bob", "person", "");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> objects.putEntity(a, "admin", UUID.randomUUID(), "Alice", "person", ""));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> objects.putRelation(a, "admin", UUID.randomUUID(), x, y, "", 1));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO rag_knowledge_base_member (id,knowledge_base_id,user_id,role) SELECT"
                    + " ?, ?, id, 'READER' FROM app_user WHERE username='admin'",
                UUID.randomUUID(),
                a));
  }

  @Test
  void jpaAndJdbcChangesShareOneRollbackBoundary() {
    UUID kb = knowledgeBases.create("admin", "rollback", "").getId(), id = UUID.randomUUID();
    transactions.executeWithoutResult(
        tx -> {
          knowledgeBases.update(kb, "admin", "rolled back", "");
          objects.putChunk(kb, "admin", id, "rollback");
          vectors.upsert(kb, "admin", VectorKind.CHUNK, id, new float[] {1, 0, 0});
          tx.setRollbackOnly();
        });
    assertEquals("rollback", knowledgeBases.get(kb, "admin").getName());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_chunk WHERE knowledge_base_id=?", Integer.class, kb));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_chunk_embedding WHERE knowledge_base_id=?",
            Integer.class,
            kb));
  }

  @Test
  void databaseVersionCommentsAndIndexesArePresent() {
    assertEquals(
        "0.8.7",
        jdbc.queryForObject(
            "SELECT extversion FROM pg_extension WHERE extname='vector'", String.class));
    int version = jdbc.queryForObject("SHOW server_version_num", Integer.class);
    assertTrue(version >= 180000 && version < 190000);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE"
                + " n.nspname='public' AND c.relkind='r' AND c.relname LIKE 'rag_%' AND"
                + " obj_description(c.oid,'pg_class') IS NULL",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN"
                + " pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND"
                + " c.relkind='r' AND c.relname LIKE 'rag_%' AND a.attnum>0 AND NOT a.attisdropped"
                + " AND col_description(c.oid,a.attnum) IS NULL",
            Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexdef LIKE '%USING"
                + " hnsw%'",
            Integer.class));
  }

  @Test
  void httpCrudMembershipAndCsrfEnforceKnowledgeBaseBoundaries() throws Exception {
    var anonymous = HttpClient.newHttpClient();
    assertEquals(
        401,
        anonymous
            .send(
                HttpRequest.newBuilder(URI.create(url(""))).build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    Session owner = new Session("admin", "123456"),
        reader = new Session("reader", "test-pass"),
        outsider = new Session("outsider", "test-pass");
    assertEquals(403, owner.send("POST", "", "{\"name\":\"missing csrf\"}", false).statusCode());
    assertEquals(400, owner.send("POST", "", "{\"name\":\" \"}", true).statusCode());
    var created =
        owner.send("POST", "", "{\"name\":\"Private KB\",\"description\":\"test\"}", true);
    assertEquals(201, created.statusCode());
    UUID kb = UUID.fromString(json.readTree(created.body()).path("id").asText());
    assertEquals(200, owner.send("GET", "/" + kb, null, true).statusCode());
    assertEquals(404, outsider.send("GET", "/" + kb, null, true).statusCode());
    assertEquals("[]", outsider.send("GET", "", null, true).body());
    assertEquals(
        204,
        owner
            .send("PUT", "/" + kb + "/members/reader", "{\"role\":\"READER\"}", true)
            .statusCode());
    assertEquals(200, reader.send("GET", "/" + kb, null, true).statusCode());
    assertEquals(403, reader.send("PUT", "/" + kb, "{\"name\":\"stolen\"}", true).statusCode());
    assertEquals(403, reader.send("DELETE", "/" + kb, null, true).statusCode());
    assertEquals(
        403,
        reader
            .send("PUT", "/" + kb + "/members/outsider", "{\"role\":\"WRITER\"}", true)
            .statusCode());
    assertEquals(
        400,
        owner.send("PUT", "/" + kb + "/members/reader", "{\"role\":\"OWNER\"}", true).statusCode());
    assertEquals(200, owner.send("PUT", "/" + kb, "{\"name\":\"Renamed\"}", true).statusCode());
    assertEquals(400, owner.send("GET", "?size=101", null, true).statusCode());
    assertEquals(204, owner.send("DELETE", "/" + kb + "/members/reader", null, true).statusCode());
    assertEquals(404, reader.send("GET", "/" + kb, null, true).statusCode());
    UUID objectId = UUID.randomUUID();
    objects.putChunk(kb, "admin", objectId, "cleanup");
    vectors.upsert(kb, "admin", VectorKind.CHUNK, objectId, new float[] {1, 0, 0});
    assertEquals(204, owner.send("DELETE", "/" + kb, null, true).statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_chunk_embedding WHERE knowledge_base_id=?",
            Integer.class,
            kb));
  }

  @Test
  void storageChecksReaderWriterAndRevocationIndependentlyOfControllers() {
    UUID kb = knowledgeBases.create("admin", "roles", "").getId(), id = UUID.randomUUID();
    knowledgeBases.grant(kb, "admin", "writer", KnowledgeRole.WRITER);
    knowledgeBases.grant(kb, "admin", "reader", KnowledgeRole.READER);
    objects.putChunk(kb, "writer", id, "written");
    vectors.upsert(kb, "writer", VectorKind.CHUNK, id, new float[] {1, 0, 0});
    assertEquals(
        1, vectors.search(kb, "reader", VectorKind.CHUNK, new float[] {1, 0, 0}, 2, 0).size());
    assertThrows(
        RagAccessException.class, () -> objects.putChunk(kb, "reader", UUID.randomUUID(), "no"));
    assertThrows(
        RagAccessException.class,
        () -> vectors.upsert(kb, "reader", VectorKind.CHUNK, id, new float[] {1, 0, 0}));
    knowledgeBases.revoke(kb, "admin", "writer");
    assertThrows(
        RagAccessException.class,
        () -> vectors.search(kb, "writer", VectorKind.CHUNK, new float[] {1, 0, 0}, 2, 0));
  }

  private void seed(UUID kb, VectorKind kind, UUID id) {
    switch (kind) {
      case CHUNK -> objects.putChunk(kb, "admin", id, "content");
      case ENTITY -> objects.putEntity(kb, "admin", id, id.toString(), "concept", "");
      case RELATION -> {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        objects.putEntity(kb, "admin", a, a.toString(), "concept", "");
        objects.putEntity(kb, "admin", b, b.toString(), "concept", "");
        objects.putRelation(kb, "admin", id, a, b, "relation", 1);
      }
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + "/api/knowledge-bases" + path;
  }

  /**
   * HTTP session helper, storing credentials only in local test requests.
   *
   * @author agent
   */
  class Session {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;

    Session(String username, String password) throws Exception {
      refresh();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                  .header("X-CSRF-TOKEN", token)
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "username=" + username + "&password=" + password))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      refresh();
    }

    void refresh() throws Exception {
      token =
          json.readTree(
                  client
                      .send(
                          HttpRequest.newBuilder(
                                  URI.create("http://localhost:" + port + "/api/auth/csrf"))
                              .build(),
                          HttpResponse.BodyHandlers.ofString())
                      .body())
              .path("token")
              .asText();
    }

    HttpResponse<String> send(String method, String path, String body, boolean csrf)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json");
      if (csrf) builder.header("X-CSRF-TOKEN", token);
      return client.send(
          builder
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
