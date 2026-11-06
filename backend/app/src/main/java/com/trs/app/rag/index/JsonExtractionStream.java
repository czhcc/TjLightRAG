package com.trs.app.rag.index;

import com.trs.app.rag.model.ModelFailure;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.core.*;
import tools.jackson.core.async.ByteArrayFeeder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.TokenBuffer;

/** Standard incremental JSON decoding followed by independent graph record validation. @author czhcc */
public final class JsonExtractionStream {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final JsonParser tokens = JSON.createNonBlockingByteArrayParser();
    private final ByteArrayFeeder feeder = (ByteArrayFeeder) tokens.nonBlockingInputFeeder();
    private final ExtractionParser normalizer;
    private final Consumer<Extraction> consumer;
    private final boolean deferRecordErrors;
    private final StringBuilder document = new StringBuilder();
    private final List<Extraction.Relation> missingEndpoints = new ArrayList<>();
    private final List<JsonNode> pendingRelations = new ArrayList<>();
    private Extraction result;
    private TokenBuffer object;
    private int stage, depth;
    private boolean relation, entitiesComplete, ended;
    private String validationFailure, pendingSurrogate = "";
    private final Set<String> fields = new HashSet<>();

    /** Starts strict validation, allowing historical string attribute input. @author czhcc */
    public JsonExtractionStream(ExtractionParser normalizer, Extraction seed, Consumer<Extraction> consumer) {
        this(normalizer, seed, consumer, false);
    }

    /** Defers semantic record errors until the complete provider response can be corrected once. @author czhcc */
    public JsonExtractionStream(
            ExtractionParser normalizer, Extraction seed, Consumer<Extraction> consumer, boolean deferRecordErrors) {
        this.normalizer = normalizer;
        this.result = normalizer.glean(new Extraction(), seed);
        this.consumer = consumer;
        this.deferRecordErrors = deferRecordErrors;
    }

    /** Feeds UTF-8 safely even when a provider delta divides a UTF-16 surrogate pair. @author czhcc */
    public void accept(String delta) {
        if (document.length() + delta.length() > 2_000_000) throw new ModelFailure("extraction_size_limit");
        if (ended) invalid();
        document.append(delta);
        String input = pendingSurrogate + delta;
        pendingSurrogate = "";
        if (!input.isEmpty() && Character.isHighSurrogate(input.charAt(input.length() - 1))) {
            pendingSurrogate = input.substring(input.length() - 1);
            input = input.substring(0, input.length() - 1);
        }
        if (input.isEmpty()) return;
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        try {
            feeder.feedInput(bytes, 0, bytes.length);
            drain();
        } catch (JacksonException malformed) {
            invalid();
        }
    }

    private void drain() {
        JsonToken token;
        while ((token = tokens.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
            if (object != null) {
                object.copyCurrentEvent(tokens);
                if (token.isStructStart() && ++depth > 16) invalid();
                if (token.isStructEnd() && --depth == 0) {
                    JsonNode record = JSON.readTree(object);
                    object.close();
                    object = null;
                    if (relation && !entitiesComplete) pendingRelations.add(record);
                    else record(record, relation);
                }
                continue;
            }
            switch (stage) {
                case 0 -> {
                    if (token != JsonToken.START_OBJECT) invalid();
                    stage = 1;
                }
                case 1 -> {
                    if (token == JsonToken.END_OBJECT) {
                        if (!fields.containsAll(Set.of("entities", "relations"))) invalid();
                        stage = 4;
                    } else {
                        if (token != JsonToken.PROPERTY_NAME) invalid();
                        String field = tokens.currentName();
                        if (!Set.of("entities", "relations").contains(field) || !fields.add(field)) invalid();
                        relation = field.equals("relations");
                        stage = 2;
                    }
                }
                case 2 -> {
                    if (token != JsonToken.START_ARRAY) invalid();
                    stage = 3;
                }
                case 3 -> {
                    if (token == JsonToken.END_ARRAY) {
                        if (!relation) {
                            entitiesComplete = true;
                            for (var value : pendingRelations) record(value, true);
                            pendingRelations.clear();
                        }
                        stage = 1;
                    } else {
                        if (token != JsonToken.START_OBJECT) invalid();
                        object = new TokenBuffer(false);
                        object.copyCurrentEvent(tokens);
                        depth = 1;
                    }
                }
                default -> invalid();
            }
        }
    }

    /** Requires a complete JSON document and valid records, independently of provider success. @author czhcc */
    public Extraction finish() {
        finishForCorrection();
        if (validationFailure != null) throw new ModelFailure(validationFailure);
        return result;
    }

    /** Exposes only valid records for correction after the complete envelope is received. @author czhcc */
    public Extraction finishForCorrection() {
        if (stage != 4 || object != null || !pendingSurrogate.isEmpty())
            throw new ModelFailure("extraction_incomplete");
        if (!ended) {
            try {
                feeder.endOfInput();
                drain();
                tokens.close();
                ended = true;
            } catch (JacksonException malformed) {
                invalid();
            }
        }
        return result;
    }

    /** Returns a safe error code without the provider payload. @author czhcc */
    public String validationFailure() {
        return validationFailure;
    }
    /** Keeps raw bounded JSON only for the correction conversation. @author czhcc */
    public String json() {
        return document.toString();
    }
    /** Reports unresolved graph endpoints. @author czhcc */
    public boolean hasMissingEndpoints() {
        return !missingEndpoints.isEmpty();
    }

    /** Resolves typed endpoints after one correction and discards remaining unresolved edges. @author czhcc */
    public void completeCorrection(Extraction corrected) {
        finish();
        result = normalizer.glean(result, corrected);
        var restored = new Extraction();
        for (var edge : missingEndpoints)
            if (endpointsExist(edge)) restored.getRelations().add(canonical(edge));
        result = normalizer.glean(result, restored);
        missingEndpoints.clear();
        consumer.accept(result);
    }

    private void record(JsonNode value, boolean isRelation) {
        try {
            readRecord(value, isRelation);
        } catch (ModelFailure failure) {
            if (!deferRecordErrors
                    || !(failure.getCode().startsWith("extraction_invalid_")
                            || Set.of("extraction_self_relation", "extraction_ambiguous_endpoint")
                                    .contains(failure.getCode()))) throw failure;
            if (validationFailure == null) validationFailure = failure.getCode();
        }
    }

    private void readRecord(JsonNode value, boolean isRelation) {
        var addition = new Extraction();
        if (!isRelation) {
            String name = field(value, "name", 512), type = type(field(value, "type", 100));
            addition.getEntities()
                    .add(new Extraction.Entity(
                            name, type, field(value, "description", 16000), attributes(value.path("attributes"))));
        } else {
            var a = endpoint(value.path("source"), value.path("sourceType"), "source");
            var b = endpoint(value.path("target"), value.path("targetType"), "target");
            if (a.equals(b)) throw new ModelFailure("extraction_self_relation");
            var edge = new Extraction.Relation(
                    a.name(),
                    b.name(),
                    field(value, "keywords", 4000),
                    field(value, "description", 16000),
                    1,
                    field(value, "type", 100),
                    a.type(),
                    b.type());
            if (endpointsExist(edge)) addition.getRelations().add(canonical(edge));
            else missingEndpoints.add(edge);
        }
        result = normalizer.glean(result, addition);
        consumer.accept(result);
    }

    private List<Extraction.Attribute> attributes(JsonNode values) {
        List<Extraction.Attribute> result = new ArrayList<>();
        if (values.isMissingNode()) return result;
        if (values.isObject()) {
            for (var property : values.properties()) {
                if (!property.getValue().isString()) throw new ModelFailure("extraction_invalid_attribute_value");
                result.add(attribute(property.getKey(), "string", property.getValue()));
            }
        } else if (values.isArray()) {
            for (var value : values)
                result.add(attribute(field(value, "name", 100), field(value, "type", 100), value.path("value")));
        } else throw new ModelFailure("extraction_invalid_attributes");
        if (result.size() > 32) throw new ModelFailure("extraction_invalid_attributes");
        return EntityIdentity.merge(List.of(), result);
    }

    private Extraction.Attribute attribute(String name, String type, JsonNode value) {
        name = normalizer.normalize(name, true);
        type = type.strip().toLowerCase(Locale.ROOT);
        if (name.isBlank()
                || name.length() > 100
                || reserved(name)
                || type.isBlank()
                || type.length() > 100
                || reserved(type)
                || value.isMissingNode()
                || value.isNull()
                || JSON.writeValueAsString(value).length() > 2000
                || value.isString() && value.asText().isBlank())
            throw new ModelFailure("extraction_invalid_attribute_value");
        boolean matches = switch (type) {
            case "string", "text", "date", "datetime" -> value.isString();
            case "integer", "int" -> value.isIntegralNumber();
            case "number", "decimal", "float" -> value.isNumber();
            case "boolean", "bool" -> value.isBoolean();
            default -> true;
        };
        if (!matches) throw new ModelFailure("extraction_invalid_attribute_value");
        return new Extraction.Attribute(name, type, value);
    }

    private record Endpoint(String name, String type) {}

    private Endpoint endpoint(JsonNode value, JsonNode explicitType, String key) {
        if (value.isObject()) return new Endpoint(field(value, "name", 512), type(field(value, "type", 100)));
        if (!value.isString()) throw new ModelFailure("extraction_invalid_field_" + key);
        String name = normalizer.normalize(value.asText(), true);
        if (name.isBlank() || name.length() > 512) throw new ModelFailure("extraction_invalid_field_" + key);
        String type = explicitType.isString() && !explicitType.asText().isBlank()
                ? type(explicitType.asText())
                : resolve(name, "");
        return new Endpoint(name, type);
    }

    private String resolve(String name, String type) {
        var matches = result.getEntities().stream()
                .filter(e -> e.getName().equals(name)
                        && (type.isBlank() || e.getType().equals(type)))
                .toList();
        if (matches.size() > 1) throw new ModelFailure("extraction_ambiguous_endpoint");
        return matches.isEmpty() ? "" : matches.getFirst().getType();
    }

    private boolean endpointsExist(Extraction.Relation edge) {
        String a = resolve(edge.getSource(), edge.getSourceType()), b = resolve(edge.getTarget(), edge.getTargetType());
        if (a.isBlank() || b.isBlank()) return false;
        edge.setSourceType(a);
        edge.setTargetType(b);
        return true;
    }

    private Extraction.Relation canonical(Extraction.Relation edge) {
        if (EntityIdentity.key(edge.getSource(), edge.getSourceType())
                        .compareTo(EntityIdentity.key(edge.getTarget(), edge.getTargetType()))
                > 0)
            return new Extraction.Relation(
                    edge.getTarget(),
                    edge.getSource(),
                    edge.getKeywords(),
                    edge.getDescription(),
                    edge.getWeight(),
                    edge.getType(),
                    edge.getTargetType(),
                    edge.getSourceType());
        return edge;
    }

    private String type(String raw) {
        String value = normalizer.normalize(raw, true).toLowerCase(Locale.ROOT);
        value = switch (value) {
            case "person", "个人", "人物" -> "人";
            case "location", "地点" -> "地";
            case "item", "物品" -> "物";
            case "event", "事件" -> "事";
            case "organization", "组织" -> "网";
            default -> value;
        };
        if (value.isBlank() || value.length() > 100 || reserved(value))
            throw new ModelFailure("extraction_invalid_entity_type");
        return value;
    }

    private String field(JsonNode value, String key, int max) {
        if (!value.path(key).isString()) throw new ModelFailure("extraction_invalid_field_" + key);
        String text = normalizer.normalize(value.path(key).asText(), !key.equals("description"));
        if (text.isBlank() || text.length() > max) throw new ModelFailure("extraction_invalid_field_" + key);
        return text;
    }

    private boolean reserved(String name) {
        return Set.of("__proto__", "prototype", "constructor").contains(name.toLowerCase(Locale.ROOT));
    }

    private void invalid() {
        throw new ModelFailure("extraction_invalid_json");
    }
}
