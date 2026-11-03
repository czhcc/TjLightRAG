package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;

/** Upgrades populated legacy model settings without losing index inputs. @author czhcc */
@Testcontainers
class ModelSettingsMigrationTest {
  @Container
  static final GenericContainer<?> DB = new GenericContainer<>(VectorStorageIntegrationTest.IMAGE)
      .withEnv("POSTGRES_DB", "migration").withEnv("POSTGRES_USER", "migration")
      .withEnv("POSTGRES_PASSWORD", "test-only").withExposedPorts(5432)
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

  @Test
  void migratesLatestSettingsAndScopedBindingsWhileKeepingReferencedSnapshots() {
    String url = "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/migration";
    var config = Flyway.configure().dataSource(url, "migration", "test-only")
        .placeholders(Map.of("ragVectorDimensions", "3", "ragEmbeddingModel", "fixture-3d"));
    config.target("11").load().migrate();
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "migration", "test-only"));
    UUID kb = UUID.randomUUID(), other = UUID.randomUUID(), chat = UUID.randomUUID(), embed = UUID.randomUUID();
    UUID oldChat = UUID.randomUUID(), latestChat = UUID.randomUUID(), oldEmbed = UUID.randomUUID(), generation = UUID.randomUUID();
    jdbc.update("INSERT INTO rag_knowledge_base(id,name) VALUES (?, 'first'), (?, 'second')", kb, other);
    jdbc.update("INSERT INTO rag_model_profile(id,name,kind) VALUES (?, 'text', 'CHAT'), (?, 'vector', 'EMBEDDING')", chat, embed);
    for (var item : List.of(new Object[] {oldChat, chat, 1, "old-chat", 0},
        new Object[] {latestChat, chat, 2, "latest-chat", 0}, new Object[] {oldEmbed, embed, 1, "embed-3", 3})) {
      jdbc.update("INSERT INTO rag_model_version(id,profile_id,version,base_url,model,secret_cipher,dimensions,context_tokens,max_output_tokens,timeout_seconds)"
          + " VALUES (?,?,?,'http://fixture/v1',?,'fixture-cipher',?,16000,2000,30)", item);
    }
    jdbc.update("INSERT INTO rag_model_binding(role,version_id) VALUES ('answer',?),('embedding',?)", oldChat, oldEmbed);
    jdbc.update("INSERT INTO rag_index_generation(knowledge_base_id,id,requested_by,extract_version,summary_version,embedding_version,dimensions,prompt_version,tokenizer)"
        + " SELECT ?,?,id,?,?,?,3,'fixture','cl100k_base' FROM app_user WHERE username='admin'", kb, generation, oldChat, oldChat, oldEmbed);

    config.target("latest").load().migrate();

    assertEquals("latest-chat", jdbc.queryForObject("SELECT model FROM rag_model_configuration WHERE id=?", String.class, chat));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rag_model_configuration", Integer.class));
    for (UUID base : List.of(kb, other)) {
      assertEquals(chat, jdbc.queryForObject("SELECT model_id FROM rag_knowledge_base_model_binding WHERE knowledge_base_id=? AND role='answer'", UUID.class, base));
      assertEquals(embed, jdbc.queryForObject("SELECT model_id FROM rag_knowledge_base_model_binding WHERE knowledge_base_id=? AND role='embedding'", UUID.class, base));
    }
    assertEquals("old-chat", jdbc.queryForObject("SELECT model FROM rag_index_model_snapshot WHERE id=?", String.class, oldChat));
    assertEquals(oldEmbed, jdbc.queryForObject("SELECT embedding_version FROM rag_index_generation WHERE id=?", UUID.class, generation));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_model_snapshot", Integer.class));
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE rag_index_model_snapshot SET model='mutated' WHERE id=?", oldChat));
    jdbc.update("DELETE FROM rag_knowledge_base_model_binding WHERE model_id=?", chat);
    jdbc.update("DELETE FROM rag_model_profile WHERE id=?", chat);
    assertEquals("fixture-cipher", jdbc.queryForObject("SELECT secret_cipher FROM rag_index_model_snapshot WHERE id=?", String.class, oldChat));
  }
}
