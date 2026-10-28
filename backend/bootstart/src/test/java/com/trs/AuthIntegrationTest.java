package com.trs;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 登录会话与安全边界集成测试。 @author agent */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"rag.ingestion.enabled=false", "rag.indexing.enabled=false"})
@org.testcontainers.junit.jupiter.Testcontainers
class AuthIntegrationTest {
  @org.testcontainers.junit.jupiter.Container
  static final org.testcontainers.containers.GenericContainer<?> DB =
      new org.testcontainers.containers.GenericContainer<>(
              "pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0")
          .withEnv("POSTGRES_DB", "authentication")
          .withEnv("POSTGRES_USER", "authentication")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withExposedPorts(5432)
          .waitingFor(
              org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                  ".*database system is ready to accept connections.*\n", 2));

  @org.springframework.test.context.DynamicPropertySource
  static void isolatedDatabase(
      org.springframework.test.context.DynamicPropertyRegistry properties) {
    String url =
        "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/authentication";
    properties.add("spring.datasource.url", () -> url);
    properties.add("spring.datasource.username", () -> "authentication");
    properties.add("spring.datasource.password", () -> "test-only");
    properties.add("rag.vector.dimensions", () -> "3");
    properties.add("rag.vector.model", () -> "fixture-3d");
  }

  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

  @org.springframework.beans.factory.annotation.Autowired org.flywaydb.core.Flyway flyway;

  @Test
  void migrationsReuseApplicationDataSource() {
    assertSame(dataSource, flyway.getConfiguration().getDataSource());
    assertTrue(flyway.info().applied().length > 0);
  }

  @Test
  void loginAndLogout() throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String base = "http://localhost:" + port;
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var csrf =
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    String token = csrf.split("\"token\":\"")[1].split("\"")[0];
    var bad =
        HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=wrong"))
            .build();
    assertEquals(401, client.send(bad, HttpResponse.BodyHandlers.ofString()).statusCode());
    var login =
        HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-CSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=123456"))
            .build();
    assertEquals(200, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertTrue(
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString())
            .body()
            .contains("admin"));
    assertEquals(
        403,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/logout"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    csrf =
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    token = csrf.split("\"token\":\"")[1].split("\"")[0];
    assertEquals(
        204,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/logout"))
                    .header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }
}
