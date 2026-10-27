package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.index.ExtractionParser;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Fixed upstream extraction and merge contracts. @author agent */
class ExtractionTest {
  private final ExtractionParser parser = new ExtractionParser();

  @Test
  void acceptsFourAndFiveFieldsAndRejectsMalformedRecords() {
    var result =
        parser.parse(
            "entity<|#|>Alice<|#|>Person<|#|>A researcher\n"
                + "relation<|#|>Alice<|#|>Bob<|#|>research，science<|#|>Works with Bob\n"
                + "entity<|#|>bad<|#|>person\n"
                + "relation<|#|>Alice<|#|>Alice<|#|>self<|#|>self\n"
                + "<|COMPLETE|>");
    assertEquals(1, result.getEntities().size());
    assertEquals("person", result.getEntities().getFirst().getType());
    assertEquals(1, result.getRelations().size());
    assertEquals("research,science", result.getRelations().getFirst().getKeywords());
    assertEquals(1.0, result.getRelations().getFirst().getWeight());
  }

  @Test
  void normalizesNamesAndRejectsReservedTypes() {
    var result =
        parser.parse(
            "entity<|#|>\"张 三\"<|#|>Person,Other<|#|>研究者\n"
                + "entity<|#|>Foo<|#|>__proto__<|#|>bad\n"
                + "<|COMPLETE|>");
    assertEquals(1, result.getEntities().size());
    assertEquals("张三", result.getEntities().getFirst().getName());
  }

  @Test
  void gleaningKeepsLongerDescriptionsWithoutDoubleCounting() {
    var a =
        parser.parse(
            "entity<|#|>A<|#|>person<|#|>short\n"
                + "relation<|#|>A<|#|>B<|#|>k<|#|>short\n"
                + "<|COMPLETE|>");
    var b =
        parser.parse(
            "entity<|#|>A<|#|>person<|#|>a longer description\n"
                + "relation<|#|>B<|#|>A<|#|>k<|#|>a longer description\n"
                + "<|COMPLETE|>");
    var merged = parser.glean(a, b);
    assertEquals(1, merged.getEntities().size());
    assertEquals("a longer description", merged.getEntities().getFirst().getDescription());
    assertEquals(1, merged.getRelations().size());
    assertEquals(1.0, merged.getRelations().getFirst().getWeight());
  }

  @Test
  void truncatedOrOversizeOutputCannotPublishPartialGraph() {
    assertThrows(RuntimeException.class, () -> parser.parse("entity<|#|>A<|#|>person<|#|>partial"));
    assertThrows(RuntimeException.class, () -> parser.parse("x".repeat(2100000)));
  }
}
