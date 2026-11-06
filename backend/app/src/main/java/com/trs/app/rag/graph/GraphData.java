package com.trs.app.rag.graph;

import java.util.*;
import lombok.Value;

/** Read-only graph projections scoped to one published index generation. @author czhcc */
public final class GraphData {
    private GraphData() {}

    /** A typed entity, retaining its native JSON attributes. @author czhcc */
    @Value
    public static class Node {
        UUID id;
        String name, type, description;
        Map<String, Object> attributes;
    }

    /** An undirected relation between entity IDs, never names. @author czhcc */
    @Value
    public static class Link {
        UUID id, source, target;
        List<String> types;
        String description;
        double weight;
    }

    /** Bounded query or expansion result; nextOffset applies to neighbor pages. @author czhcc */
    @Value
    public static class Slice {
        UUID generationId;
        List<Node> nodes;
        List<Link> links;
        boolean hasMore;
        int nextOffset;
    }

    /** Available entity types from the captured published index. @author czhcc */
    @Value
    public static class Types {
        UUID generationId;
        List<String> types;
    }
}
