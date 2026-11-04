package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.index.*;
import com.trs.app.rag.model.ModelFailure;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Incremental object boundaries, schema checks and incomplete-stream isolation. @author czhcc */
class JsonExtractionStreamTest {
  private final ExtractionParser parser = new ExtractionParser();
  @Test
  void normalizesTypesAndRejectsAmbiguousNameOnlyEndpointsWithoutGuessing() {
    var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    stream.accept("""
        {"entities":[{"name":"长安","type":"location","description":"城市","attributes":[]},
        {"name":"长安","type":"organization","description":"企业","attributes":[]}],"relations":[]}
        """);
    assertEquals(Set.of("地", "网"), new HashSet<>(stream.finish().getEntities().stream().map(Extraction.Entity::getType).toList()));
    var extra = new JsonExtractionStream(parser, stream.finish(), ignored -> {});
    assertEquals("extraction_ambiguous_endpoint", assertThrows(ModelFailure.class, () -> extra.accept("""
        {"entities":[],"relations":[{"source":"长安","target":{"name":"长安","type":"地"},"type":"位于","keywords":"城市","description":"企业位于城市"}]}
        """)).getCode());
  }

  @Test
  void standardJsonEscapedKeysAndEverySplitPointPreserveTypedData() {
    String value = "{\"enti" + "\\u0074" + "ies\":[" + PERSON + "],\"relations\":[]}";
    for (int split = 0; split <= value.length(); split++) {
      var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
      stream.accept(value.substring(0, split)); stream.accept(value.substring(split));
      assertEquals("张三", stream.finish().getEntities().getFirst().getName());
    }
    for (String raw : List.of("\"35\"", "true", "null")) {
      var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
      assertThrows(ModelFailure.class, () -> stream.accept("{\"entities\":[{\"name\":\"张三\",\"type\":\"人\",\"description\":\"个人\",\"attributes\":[{\"name\":\"年龄\",\"type\":\"integer\",\"value\":" + raw + "}]}],\"relations\":[]}"));
    }
  }

  @Test
  void supportsTypedAttributesOpenCategoriesAndTypedEndpointsInEitherFieldOrder() {
    var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    String input = """
        {"relations":[{"source":{"name":"长安","type":"地"},"target":{"name":"长安","type":"网"},"type":"位于","keywords":"城市,企业","description":"企业位于城市"}],
         "entities":[{"name":"长安","type":"地","description":"城市","attributes":[{"name":"人口","type":"integer","value":35}]},
          {"name":"长安","type":"网","description":"企业","attributes":[{"name":"上市","type":"boolean","value":true}]},
          {"name":"知识","type":"概念","description":"抽象概念","attributes":[]}]}
        """;
    for (int i = 0; i < input.length(); i++) stream.accept(input.substring(i, i + 1));
    var result = stream.finish();
    assertEquals(3, result.getEntities().size());
    assertEquals(1, result.getRelations().size());
    assertFalse(stream.hasMissingEndpoints());
    assertNotEquals(result.getRelations().getFirst().getSourceType(), result.getRelations().getFirst().getTargetType());
    var attribute = result.getEntities().getFirst().getAttributes().getFirst();
    assertEquals("integer", attribute.getType());
    assertEquals(35, attribute.getValue().asInt());
  }
  private static final String PERSON = """
      {"name":"张三","type":"人","description":"工程师🙂","attributes":{"职业":"工程师","备注":"花括号 } 和引号 \\\""}}
      """.strip();
  private static final String ORG = """
      {"name":"甲公司","type":"网","description":"科技公司","attributes":{"所在地":"北京"}}
      """.strip();
  private static final String RELATION = """
      {"source":"张三","target":"甲公司","type":"任职","keywords":"工作","description":"张三任职于甲公司"}
      """.strip();

  @Test
  void defersRecordErrorsOnlyForACompleteSuccessfulCorrectionAttempt() {
    List<Extraction> emitted = new ArrayList<>();
    var stream = new JsonExtractionStream(parser, new Extraction(), emitted::add, true);
    String invalidPerson = PERSON.replace("\"name\":\"张三\",", "");
    stream.accept("{\"entities\":[" + invalidPerson + "," + ORG + "],\"relations\":[]}");
    assertEquals("extraction_invalid_field_name", stream.validationFailure());
    assertEquals(1, stream.finishForCorrection().getEntities().size());
    assertThrows(ModelFailure.class, stream::finish);
    assertEquals(1, emitted.size());
    var incomplete = new JsonExtractionStream(parser, new Extraction(), ignored -> {}, true);
    incomplete.accept("{\"entities\":[" + invalidPerson);
    assertThrows(ModelFailure.class, incomplete::finishForCorrection);
  }

  @Test
  void reportsSafeSpecificValidationCodesWithoutReturningModelContent() {
    var cases = Map.of(
        PERSON.replace("\"人\"", "\"constructor\""), "extraction_invalid_entity_type",
        PERSON.replace("\"name\":\"张三\",", ""), "extraction_invalid_field_name",
        PERSON.replace("\"工程师🙂\"", "\"\""), "extraction_invalid_field_description",
        "{\"name\":\"张三\",\"type\":\"人\",\"description\":\"工程师\",\"attributes\":null}", "extraction_invalid_attributes");
    for (var entry : cases.entrySet()) {
      var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
      var error = assertThrows(ModelFailure.class, () -> stream.accept("{\"entities\":[" + entry.getKey() + "],\"relations\":[]}"));
      assertEquals(entry.getValue(), error.getCode());
      assertEquals(error.getCode(), error.getMessage());
    }
    var self = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    assertEquals("extraction_self_relation", assertThrows(ModelFailure.class,
        () -> self.accept("{\"entities\":[],\"relations\":[" + RELATION.replace("甲公司", "张三") + "]}")).getCode());
  }

  @Test
  void defaultsOmittedAttributesToEmptyButRejectsExplicitInvalidTypes() {
    String entity = "{\"name\":\"张三\",\"type\":\"人\",\"description\":\"工程师\"}";
    var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    stream.accept("{\"entities\":[" + entity + "],\"relations\":[]}");
    assertTrue(stream.finish().getEntities().getFirst().getAttributes().isEmpty());
    for (String invalid : List.of("null", "\"属性\"", "1", "false")) {
      var rejected = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
      String withAttributes = entity.substring(0, entity.length() - 1) + ",\"attributes\":" + invalid + "}";
      assertThrows(ModelFailure.class, () -> rejected.accept("{\"entities\":[" + withAttributes + "],\"relations\":[]}"));
    }
  }

  @Test
  void usesCompleteObjectsBeforeTheEnvelopeAndStreamFinish() {
    List<Integer> counts = new ArrayList<>();
    var stream = new JsonExtractionStream(parser, new Extraction(), value -> counts.add(value.getEntities().size() + value.getRelations().size()));
    String first = "{\"entities\":[" + PERSON;
    for (int i = 0; i < first.length(); i++) stream.accept(first.substring(i, i + 1));
    assertEquals(List.of(1), counts);
    assertThrows(ModelFailure.class, stream::finish);
    String rest = "," + ORG + "],\"relations\":[" + RELATION + "]}";
    for (int i = 0; i < rest.length(); i++) stream.accept(rest.substring(i, i + 1));
    var result = stream.finish();
    assertEquals(List.of(1, 2, 3), counts);
    assertEquals("工程师", result.getEntities().getFirst().getAttributes().stream().filter(a -> a.getName().equals("职业")).findFirst().orElseThrow().getValue().asText());
    assertEquals("任职", result.getRelations().getFirst().getType());
    assertEquals(first + rest, stream.json());
  }

  @Test
  void enforcesEntityOrderCategoriesReferencesAndJsonSyntax() {
    for (String value : List.of(
        "{\"entities\":[" + PERSON.replace("\"人\"", "\"constructor\"") + "],\"relations\":[]}",
        "{\"entities\":[" + PERSON + ",],\"relations\":[]}",
        "{\"entities\":[" + PERSON.replace("\"职业\":", "\"__proto__\":") + "],\"relations\":[]}",
        "{\"entities\":[],\"relations\":[]} trailing",
        "```json\n{\"entities\":[],\"relations\":[]}\n```")) {
      assertThrows(ModelFailure.class, () -> {
        var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
        stream.accept(value);
        stream.finish();
      });
    }
  }

  @Test
  void holdsMissingEndpointsUntilOneCorrectionAndDropsOnlyUnresolvedRelations() {
    var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    stream.accept("{\"entities\":[" + PERSON + "],\"relations\":[" + RELATION + "]}");
    assertTrue(stream.hasMissingEndpoints());
    assertTrue(stream.finish().getRelations().isEmpty());
    var correction = new JsonExtractionStream(parser, stream.finish(), ignored -> {});
    correction.accept("{\"entities\":[" + ORG + "],\"relations\":[]}");
    stream.completeCorrection(correction.finish());
    assertEquals(2, stream.finish().getEntities().size());
    assertEquals(1, stream.finish().getRelations().size());

    var unresolved = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    unresolved.accept("{\"entities\":[" + PERSON + "," + ORG + "],\"relations\":[" + RELATION
        + "," + RELATION.replace("甲公司", "乙公司") + "]}");
    assertTrue(unresolved.hasMissingEndpoints());
    unresolved.completeCorrection(unresolved.finish());
    assertEquals(2, unresolved.finish().getEntities().size());
    assertEquals(1, unresolved.finish().getRelations().size());
    assertEquals("甲公司", unresolved.finish().getRelations().getFirst().getTarget());
  }

  @Test
  void missingEndpointsDoNotBypassOtherValidationOrStreamCompletion() {
    for (String record : List.of(RELATION.replace("甲公司", "张三"), RELATION.replace("\"任职\"", "null"))) {
      var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
      assertThrows(ModelFailure.class, () -> stream.accept("{\"entities\":[],\"relations\":[" + record + "]}"));
    }
    var incomplete = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    incomplete.accept("{\"entities\":[],\"relations\":[" + RELATION);
    assertThrows(ModelFailure.class, () -> incomplete.completeCorrection(new Extraction()));
  }

  @Test
  void allowsAllFiveCategoriesAndGleaningRelationsToExistingEntities() {
    var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    stream.accept("{\"entities\":[" + PERSON + "," + ORG + "],\"relations\":[]}");
    var base = stream.finish();
    var extra = new JsonExtractionStream(parser, base, ignored -> {});
    extra.accept("{\"entities\":[],\"relations\":[" + RELATION + "]}");
    assertEquals(1, extra.finish().getRelations().size());
    for (String type : List.of("人", "地", "物", "事", "网")) {
      var one = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
      one.accept("{\"entities\":[" + PERSON.replace("\"人\"", "\"" + type + "\"") + "],\"relations\":[]}");
      assertEquals(type, one.finish().getEntities().getFirst().getType());
    }
  }

  @Test
  void preservesAttributesAndMultipleRelationTypesWhenGleaning() {
    var first = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    first.accept("{\"entities\":[" + PERSON + "," + ORG + "],\"relations\":[" + RELATION + "]}");
    var extra = new JsonExtractionStream(parser, first.finish(), ignored -> {});
    extra.accept("{\"entities\":[" + PERSON.replace("\"职业\":\"工程师\"", "\"学历\":\"硕士\"")
        + "],\"relations\":[" + RELATION.replace("任职", "创办") + "]}");
    var result = extra.finish();
    assertEquals(Set.of("职业", "备注", "学历"), new HashSet<>(result.getEntities().getFirst().getAttributes().stream().map(Extraction.Attribute::getName).toList()));
    assertEquals(2, result.getRelations().size());
  }

  @Test
  void rejectsDuplicateFieldsOversizeAndUnfinishedObjectsWithoutPublishingThem() {
    for (String body : List.of(PERSON.replace("\"name\":", "\"name\":\"重复\",\"name\":"), "{" + " ".repeat(2_000_001))) {
      List<Extraction> emitted = new ArrayList<>();
      var stream = new JsonExtractionStream(parser, new Extraction(), emitted::add);
      assertThrows(ModelFailure.class, () -> stream.accept("{\"entities\":[" + body));
      assertTrue(emitted.isEmpty());
    }
  }

  @Test
  void keepsIncompleteRecordsOutOfTheConsumer() {
    List<Extraction> emitted = new ArrayList<>();
    var incomplete = new JsonExtractionStream(parser, new Extraction(), emitted::add);
    incomplete.accept("{\"entities\":[" + PERSON.substring(0, PERSON.length() - 1));
    assertTrue(emitted.isEmpty());
    assertThrows(ModelFailure.class, incomplete::finish);
  }

  @Test
  void acceptsLargeRoundsAndGleaningWithoutEntityOrRelationCountCaps() {
    var first = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    first.accept(largeExtraction("实体", 120, 240));
    assertEquals(120, first.finish().getEntities().size());
    assertEquals(240, first.finish().getRelations().size());
    var glean = new JsonExtractionStream(parser, first.finish(), ignored -> {});
    glean.accept(largeExtraction("补充", 120, 240));
    assertEquals(240, glean.finish().getEntities().size());
    assertEquals(480, glean.finish().getRelations().size());
    glean.completeCorrection(glean.finish());
    assertEquals(480, glean.finish().getRelations().size());
  }

  @Test
  void legacyRecordsHaveNoEntityOrRelationCountCaps() {
    var text = new StringBuilder();
    for (int i = 0; i < 120; i++)
      text.append("entity<|#|>实体").append(i).append("<|#|>人<|#|>文本中人物\n");
    for (int i = 1; i < 120; i++)
      text.append("relation<|#|>实体0<|#|>实体").append(i).append("<|#|>关联<|#|>原文关系\n");
    text.append("<|COMPLETE|>");
    var result = parser.parse(text.toString());
    assertEquals(120, result.getEntities().size());
    assertEquals(119, result.getRelations().size());
  }

  @Test
  void stillRejectsOversizeJsonWithASizeSpecificError() {
    var stream = new JsonExtractionStream(parser, new Extraction(), ignored -> {});
    assertEquals("extraction_size_limit", assertThrows(ModelFailure.class,
        () -> stream.accept(" ".repeat(2_000_001))).getCode());
  }

  private String largeExtraction(String prefix, int entities, int relations) {
    var records = new ArrayList<String>();
    for (int i = 0; i < entities; i++)
      records.add("{\"name\":\"" + prefix + i + "\",\"type\":\"人\",\"description\":\"原文中的人物\",\"attributes\":[]}");
    var edges = new ArrayList<String>();
    for (int i = 0; i < relations; i++)
      edges.add("{\"source\":{\"name\":\"" + prefix + "0\",\"type\":\"人\"},\"target\":{\"name\":\""
          + prefix + (1 + i % (entities - 1)) + "\",\"type\":\"人\"},\"type\":\"关系" + i
          + "\",\"keywords\":\"关联\",\"description\":\"原文支持的关系\"}");
    return "{\"entities\":[" + String.join(",", records) + "],\"relations\":[" + String.join(",", edges) + "]}";
  }
}
