package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Real migration tests for empty databases, V1 upgrades and least-privilege DML.
 *
 * @author agent
 */
@Testcontainers
class MigrationIntegrationTest {
  @Container
  static final GenericContainer<?> DB =
      new GenericContainer<>(VectorStorageIntegrationTest.IMAGE)
          .withEnv("POSTGRES_DB", "migration")
          .withEnv("POSTGRES_USER", "migration")
          .withEnv("POSTGRES_PASSWORD", "test-only")
          .withEnv("APP_DB_PASSWORD", "runtime-only")
          .withCopyFileToContainer(
              MountableFile.forHostPath(bootstrapScript(), 0755),
              "/docker-entrypoint-initdb.d/01-runtime-user.sh")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

  private static String bootstrapScript() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path script = directory.resolve("docker/init/01-runtime-user.sh");
      if (Files.isRegularFile(script)) return script.toString();
      directory = directory.getParent();
    }
    throw new IllegalStateException("Cannot find repository database bootstrap script");
  }

  @Test
  void runtimeBootstrapCreatesRestrictedLogin() throws Exception {
    try (var connection = DriverManager.getConnection(url(), "tjlightrag_app", "runtime-only");
        var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT current_user, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE"
                    + " rolname=current_user")) {
      assertTrue(result.next());
      assertEquals("tjlightrag_app", result.getString(1));
      assertFalse(result.getBoolean(2));
      assertFalse(result.getBoolean(3));
      assertFalse(result.getBoolean(4));
    }
  }

  private String url() {
    return "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/migration";
  }

  private Flyway flyway(String schema, String target, int dimension) {
    var config =
        Flyway.configure()
            .dataSource(url(), "migration", "test-only")
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration", "classpath:db/callback")
            .placeholders(
                Map.of(
                    "ragVectorDimensions",
                    Integer.toString(dimension),
                    "ragEmbeddingModel",
                    "fixture-3d"));
    if (target != null) config.target(target);
    return config.load();
  }

  @Test
  void emptyDatabaseMigratesAndRepeatIsNoOp() {
    String schema = "fresh_" + UUID.randomUUID().toString().replace("-", "");
    assertEquals(2, flyway(schema, null, 3).migrate().migrationsExecuted);
    assertEquals(0, flyway(schema, null, 3).migrate().migrationsExecuted);
    assertThrows(
        org.flywaydb.core.api.FlywayException.class, () -> flyway(schema, null, 4).migrate());
    var changedModel =
        Flyway.configure()
            .dataSource(url(), "migration", "test-only")
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration", "classpath:db/callback")
            .placeholders(
                Map.of("ragVectorDimensions", "3", "ragEmbeddingModel", "different-model"))
            .load();
    assertThrows(org.flywaydb.core.api.FlywayException.class, changedModel::migrate);
  }

  @Test
  void existingV1UpgradePreservesAccountsAndUsesRestrictedRuntimeRole() throws Exception {
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    flyway(schema, "1", 3).migrate();
    String hash;
    try (var c = DriverManager.getConnection(url(), "migration", "test-only");
        var s = c.createStatement()) {
      s.execute("SET search_path TO " + schema + ",public");
      try (var rs = s.executeQuery("SELECT password_hash FROM app_user WHERE username='admin'")) {
        assertTrue(rs.next());
        hash = rs.getString(1);
      }
      s.execute(
          "INSERT INTO app_user (username,password_hash) VALUES ('existing-user','untouched')");
    }
    assertEquals(1, flyway(schema, null, 3).migrate().migrationsExecuted);
    try (var c = DriverManager.getConnection(url(), "migration", "test-only");
        var s = c.createStatement()) {
      s.execute("SET search_path TO " + schema + ",public");
      try (var rs = s.executeQuery("SELECT password_hash FROM app_user WHERE username='admin'")) {
        assertTrue(rs.next());
        assertEquals(hash, rs.getString(1));
      }
      try (var rs =
          s.executeQuery("SELECT password_hash FROM app_user WHERE username='existing-user'")) {
        assertTrue(rs.next());
        assertEquals("untouched", rs.getString(1));
      }
      s.execute("SET ROLE tjlightrag_app");
      s.execute("UPDATE app_user SET username=username WHERE username='existing-user'");
      assertThrows(
          SQLException.class, () -> s.execute("CREATE TABLE " + schema + ".forbidden (id INT)"));
      assertThrows(SQLException.class, () -> s.execute("DROP TABLE app_user"));
      assertThrows(
          SQLException.class, () -> s.execute("UPDATE rag_vector_configuration SET dimensions=4"));
      assertThrows(SQLException.class, () -> s.execute("DELETE FROM flyway_schema_history"));
    }
  }
}
