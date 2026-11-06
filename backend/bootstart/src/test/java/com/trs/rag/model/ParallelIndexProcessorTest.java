package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.index.*;
import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Verifies bounded parallel extraction and deterministic, fenced completion. @author czhcc */
class ParallelIndexProcessorTest {
    private final IndexService indexes = mock(IndexService.class);
    private final ModelConfigurationService models = mock(ModelConfigurationService.class);
    private final ModelGateway gateway = mock(ModelGateway.class);
    private final GraphAssembler graphs = mock(GraphAssembler.class);
    private final RagTokenizer tokenizer = mock(RagTokenizer.class);
    private final IndexLease lease = new IndexLease(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "writer",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            0,
            IndexService.PROMPT_VERSION,
            "test-tokenizer");

    private IndexProcessor processor(List<Map<String, Object>> rows) {
        when(tokenizer.getName()).thenReturn("test-tokenizer");
        when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
        when(indexes.chunks(lease)).thenReturn(rows);
        when(models.version(any()))
                .thenAnswer(call -> new ModelVersion(
                        call.getArgument(0),
                        UUID.randomUUID(),
                        "CHAT",
                        "http://localhost/v1",
                        "test",
                        "",
                        3,
                        16000,
                        1000,
                        30));
        when(gateway.embed(any(), anyString())).thenReturn(new float[] {1, 0, 0});
        return new IndexProcessor(indexes, models, gateway, new ExtractionParser(), graphs, tokenizer);
    }

    private List<Map<String, Object>> rows(int count) {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) {
            rows.add(Map.of(
                    "chunk_id", new UUID(0, i + 1), "completed", false, "content", "chunk-" + i, "headings", "[]"));
        }
        return rows;
    }

    private void output(org.mockito.invocation.InvocationOnMock call) {
        call.<Runnable>getArgument(3).run();
        call.<Consumer<String>>getArgument(2).accept("{\"entities\":[],\"relations\":[]}");
    }

    @Test
    void runsThreeChunksAtOnceAndMergesInSourceQueryOrder() throws Exception {
        var rows = rows(7);
        var processor = processor(rows);
        var started = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var othersFinished = new CountDownLatch(6);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var finished = new AtomicInteger();
        doAnswer(call -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        boolean firstChunk = call.<List<Map<String, String>>>getArgument(1)
                                .getLast()
                                .get("content")
                                .contains("chunk-0");
                        if (firstChunk) assertTrue(othersFinished.await(5, TimeUnit.SECONDS));
                        output(call);
                        finished.incrementAndGet();
                        if (!firstChunk) othersFinished.countDown();
                        return null;
                    } finally {
                        active.decrementAndGet();
                    }
                })
                .when(gateway)
                .streamJson(any(), anyList(), any(), any());
        when(graphs.assemble(anyMap(), any(), any(), any())).thenAnswer(call -> {
            assertEquals(7, finished.get(), "Merge must wait for every extraction");
            Map<UUID, Extraction> contributions = call.getArgument(0);
            assertEquals(
                    rows.stream().map(row -> row.get("chunk_id")).toList(), new ArrayList<>(contributions.keySet()));
            return new GraphAssembler.Graph(List.of(), List.of());
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> processor.process(lease));
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS), "Three model calls must overlap");
                assertEquals(3, active.get());
                verifyNoInteractions(graphs);
            } finally {
                release.countDown();
            }
            future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(3, peak.get());
        verify(indexes, times(7)).checkpoint(eq(lease), any(), any(), any());
        verify(indexes).complete(eq(lease), any());
        verify(indexes, never()).fail(any(), anyString());
    }

    @Test
    void reusesCompletedChunksWithoutCallingModels() {
        var rows = rows(3);
        var completed = new HashMap<>(rows.get(1));
        completed.put("completed", true);
        completed.put("extraction", "{\"entities\":[],\"relations\":[]}");
        rows.set(1, completed);
        var processor = processor(rows);
        doAnswer(call -> {
                    output(call);
                    return null;
                })
                .when(gateway)
                .streamJson(any(), anyList(), any(), any());
        when(graphs.assemble(anyMap(), any(), any(), any())).thenReturn(new GraphAssembler.Graph(List.of(), List.of()));
        processor.process(lease);
        verify(gateway, times(2)).streamJson(any(), anyList(), any(), any());
        verify(gateway, times(2)).embed(any(), anyString());
        verify(indexes, never()).checkpoint(eq(lease), eq((UUID) completed.get("chunk_id")), any(), any());
        verify(graphs).assemble(argThat(map -> map.size() == 3), any(), any(), any());
        verify(indexes).complete(eq(lease), any());
    }

    @Test
    void failureCancelsOtherCallsBeforeMarkingGenerationFailed() {
        var processor = processor(rows(8));
        var started = new CountDownLatch(3);
        var active = new AtomicInteger();
        var interrupted = new AtomicInteger();
        doAnswer(call -> {
                    active.incrementAndGet();
                    boolean fails = call.<List<Map<String, String>>>getArgument(1)
                            .getLast()
                            .get("content")
                            .contains("chunk-2");
                    started.countDown();
                    try {
                        assertTrue(started.await(3, TimeUnit.SECONDS));
                        if (fails) throw new ModelFailure("model_http_error");
                        try {
                            new CountDownLatch(1).await();
                            fail("Sibling should be interrupted");
                        } catch (InterruptedException stopped) {
                            interrupted.incrementAndGet();
                            Thread.currentThread().interrupt();
                            throw new ModelFailure("model_interrupted");
                        }
                        return null;
                    } finally {
                        active.decrementAndGet();
                    }
                })
                .when(gateway)
                .streamJson(any(), anyList(), any(), any());
        doAnswer(call -> {
                    assertEquals(0, active.get());
                    return null;
                })
                .when(indexes)
                .fail(eq(lease), anyString());
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> processor.process(lease));
        assertEquals(2, interrupted.get());
        verify(gateway, times(3)).streamJson(any(), anyList(), any(), any());
        verify(indexes).fail(lease, "model_http_error");
        verify(indexes, never()).checkpoint(any(), any(), any(), any());
        verify(indexes, never()).complete(any(), any());
        verifyNoInteractions(graphs);
    }

    @Test
    void lostLeaseCancelsIdleCallsAndDoesNotScheduleMoreChunks() throws Exception {
        var processor = processor(rows(8));
        var started = new CountDownLatch(3);
        var revoked = new AtomicBoolean();
        var interrupted = new AtomicInteger();
        doAnswer(call -> {
                    if (revoked.get()) throw new ModelFailure("index_lease_lost");
                    return null;
                })
                .when(indexes)
                .check(lease);
        doAnswer(call -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException stopped) {
                        interrupted.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw new ModelFailure("model_interrupted");
                    }
                    return null;
                })
                .when(gateway)
                .streamJson(any(), anyList(), any(), any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> processor.process(lease));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                revoked.set(true);
                future.get(5, TimeUnit.SECONDS);
            } finally {
                future.cancel(true);
            }
        }
        assertEquals(3, interrupted.get());
        verify(gateway, times(3)).streamJson(any(), anyList(), any(), any());
        verify(indexes).fail(lease, "index_lease_lost");
        verify(indexes, never()).checkpoint(any(), any(), any(), any());
        verify(indexes, never()).complete(any(), any());
        verifyNoInteractions(graphs);
    }

    @Test
    void callerInterruptionStopsAllWorkersAndPreservesInterruptFlag() throws Exception {
        var processor = processor(rows(8));
        var started = new CountDownLatch(3);
        var interrupted = new AtomicInteger();
        var callerInterrupted = new AtomicBoolean();
        doAnswer(call -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException stopped) {
                        interrupted.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw new ModelFailure("model_interrupted");
                    }
                    return null;
                })
                .when(gateway)
                .streamJson(any(), anyList(), any(), any());
        var caller = new Thread(() -> {
            processor.process(lease);
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            caller.interrupt();
            caller.join(5000);
        }
        assertFalse(caller.isAlive());
        assertTrue(callerInterrupted.get());
        assertEquals(3, interrupted.get());
        verify(indexes).fail(lease, "model_interrupted");
        verify(indexes, never()).checkpoint(any(), any(), any(), any());
        verify(indexes, never()).complete(any(), any());
        verifyNoInteractions(graphs);
    }
}
