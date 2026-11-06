package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.index.*;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.ModelGateway;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Verifies reuse against raw contributions, including removal and typed identities. @author czhcc */
class IncrementalGraphTest {
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final RagTokenizer tokenizer = mock(RagTokenizer.class);

    private GraphAssembler assembler() {
        when(gateway.embed(any(), anyString())).thenReturn(new float[] {1, 0, 0});
        when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
        return new GraphAssembler(gateway, tokenizer);
    }

    private Extraction contribution(String description) {
        var relation = new Extraction.Relation("Alice", "Acme", "works", "Employment", 1);
        relation.setSourceType("person");
        relation.setTargetType("organization");
        return new Extraction(
                List.of(
                        new Extraction.Entity("Alice", "person", description),
                        new Extraction.Entity("Acme", "organization", "Company")),
                List.of(relation));
    }

    private GraphAssembler.Reuse baseline(GraphAssembler.Graph graph) {
        var nodes = new HashMap<UUID, GraphAssembler.Cached>();
        var edges = new HashMap<UUID, GraphAssembler.Cached>();
        graph.getEntities()
                .forEach(n ->
                        nodes.put(n.getId(), new GraphAssembler.Cached(n.getContributionHash(), n.getDescription())));
        graph.getRelations()
                .forEach(r ->
                        edges.put(r.getId(), new GraphAssembler.Cached(r.getContributionHash(), r.getDescription())));
        return new GraphAssembler.Reuse(UUID.randomUUID(), nodes, edges);
    }

    @Test
    void unchangedGraphReusesEverySummaryAndVector() {
        var assembler = assembler();
        var chunks = Map.of(UUID.randomUUID(), contribution("Engineer"));
        var first = assembler.assemble(chunks, null, null, () -> {});
        clearInvocations(gateway);
        var next = assembler.assemble(chunks, null, null, () -> {}, baseline(first));
        assertEquals(2, next.getReusedEntities().size());
        assertEquals(1, next.getReusedRelations().size());
        verifyNoInteractions(gateway);
    }

    @Test
    void editingEntityAttributesOnlyRecomputesThatEntity() {
        var assembler = assembler();
        UUID chunk = UUID.randomUUID();
        var old = contribution("Engineer");
        var first = assembler.assemble(Map.of(chunk, old), null, null, () -> {});
        var changed = contribution("Engineer");
        changed.getEntities()
                .getFirst()
                .setAttributes(List.of(new Extraction.Attribute(
                        "role",
                        "string",
                        tools.jackson.databind.json.JsonMapper.builder().build().valueToTree("Lead"))));
        clearInvocations(gateway);
        var next = assembler.assemble(Map.of(chunk, changed), null, null, () -> {}, baseline(first));
        assertEquals(Set.of(EntityIdentity.id("Acme", "organization")), next.getReusedEntities());
        assertEquals(1, next.getReusedRelations().size());
        verify(gateway, times(1)).embed(any(), anyString());
    }

    @Test
    void removingDocumentRemovesItsContributionsAndKeepsUnrelatedObjects() {
        var assembler = assembler();
        UUID removed = UUID.randomUUID(), retained = UUID.randomUUID(), independent = UUID.randomUUID();
        var other = new Extraction(List.of(new Extraction.Entity("Alice", "city", "City")), List.of());
        var discarded = contribution("Engineer");
        discarded.setEntities(new ArrayList<>(discarded.getEntities()));
        discarded.getEntities().add(new Extraction.Entity("Only in removed document", "物", "Temporary"));
        var first = assembler.assemble(
                Map.of(removed, discarded, retained, contribution("Researcher"), independent, other),
                null,
                null,
                () -> {});
        clearInvocations(gateway);
        var next = assembler.assemble(
                Map.of(retained, contribution("Researcher"), independent, other),
                null,
                null,
                () -> {},
                baseline(first));
        assertEquals(Set.of(EntityIdentity.id("Alice", "city")), next.getReusedEntities());
        assertEquals(3, next.getEntities().size());
        assertTrue(next.getReusedRelations().isEmpty());
        assertEquals(1, next.getRelations().getFirst().getWeight());
        var person = next.getEntities().stream()
                .filter(n -> n.getType().equals("person"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(retained), person.getSources());
        assertFalse(person.getDescription().contains("Engineer"));
        verify(gateway, times(3)).embed(any(), anyString());
    }

    @Test
    void relationshipTypeChangeOnlyRecomputesThatRelationship() {
        var assembler = assembler();
        UUID chunk = UUID.randomUUID();
        var first = assembler.assemble(Map.of(chunk, contribution("Engineer")), null, null, () -> {});
        var changed = contribution("Engineer");
        changed.getRelations().getFirst().setType("创办");
        clearInvocations(gateway);
        var next = assembler.assemble(Map.of(chunk, changed), null, null, () -> {}, baseline(first));
        assertEquals(2, next.getReusedEntities().size());
        assertTrue(next.getReusedRelations().isEmpty());
        assertEquals(List.of("创办"), next.getRelations().getFirst().getTypes());
        verify(gateway, times(1)).embed(any(), anyString());
    }

    @Test
    void oldObjectsWithoutContributionHashesAreRecomputed() {
        var assembler = assembler();
        var chunks = Map.of(UUID.randomUUID(), contribution("Engineer"));
        var first = assembler.assemble(chunks, null, null, () -> {});
        var legacy = new HashMap<UUID, GraphAssembler.Cached>();
        first.getEntities().forEach(n -> legacy.put(n.getId(), new GraphAssembler.Cached(null, "old")));
        clearInvocations(gateway);
        var next = assembler.assemble(
                chunks, null, null, () -> {}, new GraphAssembler.Reuse(UUID.randomUUID(), legacy, Map.of()));
        assertTrue(next.getReusedEntities().isEmpty());
        verify(gateway, times(3)).embed(any(), anyString());
    }
}
