package com.trs.app.rag.index;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Full-generation deterministic graph merge from immutable chunk contributions. @author agent */
@Component
@RequiredArgsConstructor
public class GraphAssembler {
    private final ModelGateway gateway;
    private final RagTokenizer tokenizer;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Final graph publication batch; vectors remain separated by collection. @author agent */
    @Value
    @AllArgsConstructor
    public static class Graph {
        List<Node> entities;
        List<Edge> relations;
        UUID reuseGeneration;
        Set<UUID> reusedEntities;
        Set<UUID> reusedRelations;

        /** Creates a graph without a reusable source generation. @author czhcc */
        public Graph(List<Node> entities, List<Edge> relations) {
            this(entities, relations, null, Set.of(), Set.of());
        }
    }

    /** Published model output identified by its complete raw contribution digest. @author czhcc */
    @Value
    public static class Cached {
        String contributionHash;
        String description;
    }

    /** Compatible, immutable published generation; vectors are copied inside PostgreSQL. @author czhcc */
    @Value
    public static class Reuse {
        UUID generation;
        Map<UUID, Cached> entities;
        Map<UUID, Cached> relations;
    }

    /** Merged node including unabridged source IDs. @author agent */
    @Getter
    @Setter
    public static class Node {
        private UUID id;
        private String name, type, description;
        private List<UUID> sources;
        private float[] vector;
        private List<Extraction.Attribute> attributes = new ArrayList<>();
        private String contributionHash;
    }

    /** Merged undirected edge with one weight contribution per shared chunk. @author agent */
    @Getter
    @Setter
    public static class Edge {
        private UUID id, source, target;
        private String description, keywords;
        private double weight;
        private List<UUID> sources;
        private float[] vector;
        private List<String> types = new ArrayList<>();
        private String contributionHash;
    }

    private static class NodeParts {
        String name, type;
        final LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        final Set<UUID> sources = new LinkedHashSet<>();
        List<Extraction.Attribute> attributes = new ArrayList<>();
    }

    private static class EdgeParts {
        final LinkedHashSet<String> descriptions = new LinkedHashSet<>(), keywords = new LinkedHashSet<>();
        final Set<UUID> sources = new LinkedHashSet<>();
        final Set<String> types = new TreeSet<>();
    }

    /**
     * Rebuilds merges, summaries and vectors without accumulating previous attempt weights. @author
     * agent
     */
    public Graph assemble(
            Map<UUID, Extraction> contributions, ModelVersion summary, ModelVersion embedding, Runnable check) {
        return assemble(contributions, summary, embedding, check, null);
    }

    /** Recomputes only objects whose raw facts, attributes or source blocks changed. @author czhcc */
    public Graph assemble(
            Map<UUID, Extraction> contributions,
            ModelVersion summary,
            ModelVersion embedding,
            Runnable check,
            Reuse reuse) {
        Set<UUID> reusedEntities = new HashSet<>(), reusedRelations = new HashSet<>();
        Map<String, NodeParts> nodes = new TreeMap<>();
        Map<List<String>, EdgeParts> edges = new LinkedHashMap<>();
        for (var chunk : contributions.entrySet()) {
            check.run();
            for (var e : chunk.getValue().getEntities()) {
                var n = nodes.computeIfAbsent(EntityIdentity.key(e.getName(), e.getType()), k -> new NodeParts());
                n.name = e.getName();
                n.type = e.getType();
                n.descriptions.add(e.getDescription()
                        + (e.getAttributes().isEmpty() ? "" : "\n实体属性: " + json.writeValueAsString(e.getAttributes())));
                n.attributes = EntityIdentity.merge(n.attributes, e.getAttributes());
                if (n.attributes.size() > 8192
                        || n.attributes.stream()
                                        .map(Extraction.Attribute::getName)
                                        .distinct()
                                        .count()
                                > 128) throw new ModelFailure("graph_size_limit");
                n.sources.add(chunk.getKey());
            }
            for (var r : chunk.getValue().getRelations()) {
                String source = endpoint(nodes, r.getSource(), r.getSourceType());
                String target = endpoint(nodes, r.getTarget(), r.getTargetType());
                if (source.equals(target)) throw new ModelFailure("extraction_self_relation");
                List<String> endpoints =
                        source.compareTo(target) <= 0 ? List.of(source, target) : List.of(target, source);
                var edge = edges.computeIfAbsent(endpoints, k -> new EdgeParts());
                edge.descriptions.add(r.getDescription());
                if (!r.getType().isBlank()) {
                    edge.types.add(r.getType());
                    edge.keywords.add(r.getType());
                }
                for (String k : r.getKeywords().split(",")) if (!k.isBlank()) edge.keywords.add(k.strip());
                edge.sources.add(chunk.getKey());
                for (String key : endpoints) {
                    var n = nodes.get(key);
                    n.sources.add(chunk.getKey());
                }
            }
            if (nodes.size() > 10000 || edges.size() > 30000) throw new ModelFailure("graph_size_limit");
        }
        List<Node> resultNodes = new ArrayList<>();
        Map<String, UUID> ids = new HashMap<>();
        for (var e : nodes.entrySet()) {
            check.run();
            Node n = new Node();
            n.setId(EntityIdentity.id(e.getValue().name, e.getValue().type));
            ids.put(e.getKey(), n.getId());
            n.setName(e.getValue().name);
            n.setType(e.getValue().type);
            var desc = e.getValue().descriptions;
            if (desc.isEmpty()) {
                for (var edge : edges.entrySet())
                    if (edge.getKey().contains(e.getKey())) desc.addAll(edge.getValue().descriptions);
            }
            n.setSources(new ArrayList<>(e.getValue().sources));
            n.setAttributes(e.getValue().attributes);
            n.setContributionHash(digest(List.of(
                    e.getKey(),
                    new TreeSet<>(desc),
                    EntityIdentity.attributes(n.getAttributes()),
                    new TreeSet<>(n.getSources()))));
            Cached previous = reuse == null ? null : reuse.getEntities().get(n.getId());
            if (previous != null && n.getContributionHash().equals(previous.getContributionHash())) {
                n.setDescription(previous.getDescription());
                reusedEntities.add(n.getId());
            } else {
                n.setDescription(describe(
                        "entity", n.getName() + " (" + n.getType() + ")", new ArrayList<>(desc), summary, check));
                n.setVector(gateway.embed(embedding, n.getName() + "\n" + n.getType() + "\n" + n.getDescription()));
            }
            check.run();
            resultNodes.add(n);
        }
        List<Edge> resultEdges = new ArrayList<>();
        for (var e : edges.entrySet()) {
            check.run();
            var r = new Edge();
            String src = e.getKey().get(0), tgt = e.getKey().get(1);
            UUID a = ids.get(src), b = ids.get(tgt);
            r.setId(UUID.nameUUIDFromBytes((src + "\u0000" + tgt).getBytes(StandardCharsets.UTF_8)));
            if (a.toString().compareTo(b.toString()) > 0) {
                UUID temp = a;
                a = b;
                b = temp;
            }
            r.setSource(a);
            r.setTarget(b);
            r.setContributionHash(digest(List.of(
                    e.getKey(),
                    new TreeSet<>(e.getValue().descriptions),
                    new TreeSet<>(e.getValue().keywords),
                    e.getValue().types,
                    new TreeSet<>(e.getValue().sources))));
            Cached previous = reuse == null ? null : reuse.getRelations().get(r.getId());
            if (previous != null && r.getContributionHash().equals(previous.getContributionHash())) {
                r.setDescription(previous.getDescription());
                reusedRelations.add(r.getId());
            } else {
                r.setDescription(describe(
                        "relationship",
                        nodes.get(src).name + " (" + nodes.get(src).type + ")~" + nodes.get(tgt).name + " ("
                                + nodes.get(tgt).type + ")",
                        new ArrayList<>(e.getValue().descriptions),
                        summary,
                        check));
            }
            r.setKeywords(
                    String.join(",", e.getValue().keywords.stream().sorted().toList()));
            r.setTypes(new ArrayList<>(e.getValue().types));
            r.setSources(new ArrayList<>(e.getValue().sources));
            r.setWeight(r.getSources().size());
            if (!reusedRelations.contains(r.getId()))
                r.setVector(gateway.embed(
                        embedding,
                        r.getKeywords() + "\t" + nodes.get(src).name + " (" + nodes.get(src).type + ")\n"
                                + nodes.get(tgt).name + " (" + nodes.get(tgt).type + ")\n" + r.getDescription()));
            check.run();
            resultEdges.add(r);
        }
        return new Graph(
                resultNodes,
                resultEdges,
                reuse == null ? null : reuse.getGeneration(),
                Set.copyOf(reusedEntities),
                Set.copyOf(reusedRelations));
    }

    private String digest(Object facts) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(json.writeValueAsString(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String endpoint(Map<String, NodeParts> nodes, String name, String type) {
        if (!type.isBlank()) {
            String key = EntityIdentity.key(name, type);
            if (!nodes.containsKey(key)) throw new ModelFailure("extraction_missing_endpoint");
            return key;
        }
        var matches = nodes.entrySet().stream()
                .filter(e -> e.getValue().name.equals(name))
                .map(Map.Entry::getKey)
                .toList();
        if (matches.size() > 1) throw new ModelFailure("extraction_ambiguous_endpoint");
        if (matches.size() == 1) return matches.getFirst();
        // Compatibility for legacy delimiter-based contributions; new JSON extraction resolves typed endpoints first.
        String key = EntityIdentity.key(name, "unknown");
        var node = new NodeParts();
        node.name = name;
        node.type = "unknown";
        nodes.put(key, node);
        return key;
    }

    /**
     * Deduplicates descriptions and performs bounded hierarchical summaries without dropping facts
     * locally. @author agent
     */
    public String describe(String kind, String name, List<String> descriptions, ModelVersion model, Runnable check) {
        List<String> current =
                descriptions.stream().filter(s -> !s.isBlank()).distinct().toList();
        if (current.isEmpty()) throw new ModelFailure("graph_description_missing");
        String joined = String.join("<SEP>", current);
        if (current.size() < 10 && tokenizer.encode(joined).length <= 1000) return joined;
        String template = PromptTemplates.format(
                PromptTemplates.load("summary.txt"),
                Map.of(
                        "language",
                        "English",
                        "summary_length",
                        "1000",
                        "description_type",
                        kind,
                        "description_name",
                        name));
        int available = model.getContextTokens() - model.getMaxOutputTokens() - tokenizer.encode(template).length - 128;
        if (available < 128) throw new ModelFailure("model_context_exceeded");
        for (int pass = 0; pass < 8; pass++) {
            check.run();
            List<String> pieces = new ArrayList<>();
            for (String s : current) pieces.addAll(split(s, Math.min(available / 2, 4096)));
            List<String> next = new ArrayList<>(), batch = new ArrayList<>();
            int used = 0;
            for (String s : pieces) {
                String line = json.writeValueAsString(Map.of("description", s));
                int size = tokenizer.encode(line).length + 2;
                if (!batch.isEmpty() && used + size > available) {
                    next.add(summarize(template, batch, model, check));
                    batch.clear();
                    used = 0;
                }
                batch.add(line);
                used += size;
            }
            if (!batch.isEmpty()) next.add(summarize(template, batch, model, check));
            if (next.size() == 1 && tokenizer.encode(next.getFirst()).length <= 1000) return next.getFirst();
            if (String.join("", next).equals(String.join("", current)))
                throw new ModelFailure("summary_not_converging");
            current = next;
        }
        throw new ModelFailure("summary_not_converging");
    }

    private String summarize(String template, List<String> lines, ModelVersion model, Runnable check) {
        check.run();
        String result = gateway.chat(
                model,
                List.of(Map.of(
                        "role", "user", "content", template.replace("{description_list}", String.join("\n", lines)))));
        check.run();
        return result.strip();
    }

    private List<String> split(String source, int budget) {
        List<String> parts = new ArrayList<>();
        String remaining = source;
        while (tokenizer.encode(remaining).length > budget) {
            int count = remaining.codePointCount(0, remaining.length()), low = 1, high = count;
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (tokenizer.encode(remaining.substring(0, remaining.offsetByCodePoints(0, mid))).length <= budget)
                    low = mid;
                else high = mid - 1;
            }
            int end = remaining.offsetByCodePoints(0, low);
            parts.add(remaining.substring(0, end));
            remaining = remaining.substring(end);
        }
        if (!remaining.isEmpty()) parts.add(remaining);
        return parts;
    }
}
