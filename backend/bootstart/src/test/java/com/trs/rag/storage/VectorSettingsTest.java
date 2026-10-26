package com.trs.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.VectorSettings;
import org.junit.jupiter.api.Test;

/**
 * Validates migration-placeholder inputs without touching a database.
 *
 * @author agent
 */
class VectorSettingsTest {
  @Test
  void rejectsUnsupportedDimensionsAndUnsafeModelIdentifiers() {
    for (int dimension : new int[] {0, -1, 2001}) {
      assertThrows(
          IllegalArgumentException.class, () -> new VectorSettings(dimension, "fixture-3d"));
    }
    for (String model :
        new String[] {"", "space model", "quote'", "x;DROP TABLE app_user", "x".repeat(129)}) {
      assertThrows(IllegalArgumentException.class, () -> new VectorSettings(3, model));
    }
  }

  @Test
  void preservesExplicitModelSpace() {
    var settings = new VectorSettings(1536, "provider/model-v1");
    assertEquals(1536, settings.getDimensions());
    assertEquals("provider/model-v1", settings.getModel());
  }
}
