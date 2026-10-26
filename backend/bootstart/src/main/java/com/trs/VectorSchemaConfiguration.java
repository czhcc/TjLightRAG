package com.trs;

import com.trs.app.rag.VectorSettings;
import java.util.HashMap;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Validates vector settings before migration and verifies the retained schema at startup.
 *
 * @author agent
 */
@Configuration
public class VectorSchemaConfiguration {
  /**
   * Supplies only validated non-secret migration placeholders.
   *
   * @param settings vector space settings
   * @return Flyway customizer
   * @author agent
   */
  @Bean
  public FlywayConfigurationCustomizer vectorPlaceholders(VectorSettings settings) {
    return configuration -> {
      var placeholders = new HashMap<>(configuration.getPlaceholders());
      placeholders.put("ragVectorDimensions", Integer.toString(settings.getDimensions()));
      placeholders.put("ragEmbeddingModel", settings.getModel());
      configuration.placeholders(placeholders);
    };
  }

  /**
   * Fails startup when a retained vector schema does not match the selected model space.
   *
   * @param jdbc runtime JDBC client
   * @param settings selected model settings
   * @return startup readiness verification
   * @author agent
   */
  @Bean
  public ApplicationRunner verifyVectorSchema(JdbcTemplate jdbc, VectorSettings settings) {
    return args -> {
      var actual =
          jdbc.queryForMap("SELECT dimensions,model_id FROM rag_vector_configuration WHERE id=1");
      if (!actual.get("dimensions").equals(settings.getDimensions())
          || !actual.get("model_id").equals(settings.getModel()))
        throw new IllegalStateException(
            "Vector schema/model mismatch; explicit reindex migration is required");
      var version =
          jdbc.queryForObject(
              "SELECT extversion FROM pg_extension WHERE extname='vector'", String.class);
      if (version == null || !version.startsWith("0.8."))
        throw new IllegalStateException("Expected validated pgvector 0.8.x");
    };
  }
}
