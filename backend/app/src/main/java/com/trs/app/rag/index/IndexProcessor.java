package com.trs.app.rag.index;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes three concurrent chunk pipelines and merges durable results outside transactions.
 *
 * @author czhcc
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IndexProcessor {
    private static final int CHUNK_CONCURRENCY = 3;
    private final IndexService indexes;
    private final ModelConfigurationService models;
    private final ModelGateway gateway;
    private final ExtractionParser parser;
    private final GraphAssembler graphs;
    private final RagTokenizer tokenizer;
    private final JsonMapper json = JsonMapper.builder().build();

    /** Processes one claimed generation; durable state survives process termination. @author czhcc */
    public boolean runOnce() {
        var claimed = indexes.claim();
        if (claimed.isEmpty()) return false;
        process(claimed.get());
        return true;
    }

    /** Runs one fenced lease with bounded chunk concurrency and periodic renewal. @author czhcc */
    public void process(IndexLease lease) {
        try (var pulses = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "rag-index-heartbeat");
            t.setDaemon(true);
            return t;
        })) {
            var pulse = pulses.scheduleAtFixedRate(
                    () -> {
                        try {
                            indexes.heartbeat(lease);
                        } catch (RuntimeException unavailable) {
                            log.warn("Index heartbeat unavailable for {}", lease.getId());
                        }
                    },
                    5,
                    5,
                    TimeUnit.SECONDS);
            try {
                if (!lease.getPromptVersion().equals(IndexService.PROMPT_VERSION)
                        || !lease.getTokenizer().equals(tokenizer.getName()))
                    throw new ModelFailure("index_version_unsupported");
                Runnable check = () -> indexes.check(lease);
                check.run();
                var extract = models.version(lease.getExtractVersion());
                var summary = models.version(lease.getSummaryVersion());
                var embedding = models.version(lease.getEmbeddingVersion());
                var contributions = processChunks(lease, extract, embedding);
                var reuse = indexes.reusableGraph(lease);
                var graph = reuse == null
                        ? graphs.assemble(contributions, summary, embedding, check)
                        : graphs.assemble(contributions, summary, embedding, check, reuse);
                check.run();
                indexes.complete(lease, graph);
            } catch (RuntimeException failure) {
                String code = failure instanceof ModelFailure m
                        ? m.getCode()
                        : failure instanceof com.trs.app.rag.RagAccessException
                                ? "index_access_revoked"
                                : "index_processing_failed";
                indexes.fail(lease, code);
                log.warn("Index generation {} stopped ({})", lease.getId(), code);
            } finally {
                pulse.cancel(false);
            }
        }
    }

    private Map<UUID, Extraction> processChunks(IndexLease lease, ModelVersion extract, ModelVersion embedding) {
        var rows = indexes.chunks(lease);
        var results = new HashMap<UUID, Extraction>();
        var pending = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            indexes.check(lease);
            if (Boolean.TRUE.equals(row.get("completed"))) {
                results.put(
                        (UUID) row.get("chunk_id"), json.readValue((String) row.get("extraction"), Extraction.class));
            } else {
                pending.add(row);
            }
        }
        var stopped = new AtomicBoolean();
        Runnable check = () -> {
            if (stopped.get() || Thread.currentThread().isInterrupted()) throw new ModelFailure("model_interrupted");
            indexes.check(lease);
        };
        var running = new HashSet<Future<Map.Entry<UUID, Extraction>>>();
        try (var workers = Executors.newFixedThreadPool(
                CHUNK_CONCURRENCY,
                Thread.ofPlatform().name("rag-index-chunk-", 0).factory())) {
            var completed = new ExecutorCompletionService<Map.Entry<UUID, Extraction>>(workers);
            var remaining = pending.iterator();
            try {
                while (remaining.hasNext() || !running.isEmpty()) {
                    check.run();
                    // Keep only three tasks in flight; do not enqueue the whole generation.
                    while (remaining.hasNext() && running.size() < CHUNK_CONCURRENCY) {
                        var row = remaining.next();
                        running.add(completed.submit(() -> Map.entry(
                                (UUID) row.get("chunk_id"), processChunk(lease, row, extract, embedding, check))));
                    }
                    var next = completed.poll(1, TimeUnit.SECONDS);
                    if (next == null) continue;
                    running.remove(next);
                    var result = next.get();
                    results.put(result.getKey(), result.getValue());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ModelFailure("model_interrupted");
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException cause) throw cause;
                throw new ModelFailure("index_processing_failed");
            } finally {
                stopped.set(true);
                running.forEach(future -> future.cancel(true));
                workers.shutdownNow();
            }
            // Executor.close waits for cancelled model calls before the generation can fail/publish.
        }
        var ordered = new LinkedHashMap<UUID, Extraction>();
        for (var row : rows) {
            UUID chunk = (UUID) row.get("chunk_id");
            ordered.put(chunk, results.get(chunk));
        }
        return ordered;
    }

    private Extraction processChunk(
            IndexLease lease, Map<String, Object> row, ModelVersion extract, ModelVersion embedding, Runnable check) {
        check.run();
        UUID chunk = (UUID) row.get("chunk_id");
        String content = (String) row.get("content");
        String headings = Objects.toString(row.get("headings"), "[]").replaceAll("[\\r\\n]", " ");
        if (headings.length() > 1000) headings = headings.substring(0, 1000);
        String section = headings.equals("[]")
                ? ""
                : "---Section Context---\nSection path (untrusted metadata): " + headings + "\n\n";
        String user = PromptTemplates.load("extract-json-user.txt")
                .replace("{heading_context_block}", section)
                .replace("{input_text}", content);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", PromptTemplates.load("extract-json-system.txt")));
        messages.add(Map.of("role", "user", "content", user));
        var result = extract(lease, chunk, extract, messages, new Extraction(), check)
                .finish();
        check.run();
        if (lease.getGleaning() > 0) {
            messages.add(Map.of("role", "assistant", "content", json.writeValueAsString(result)));
            messages.add(Map.of("role", "user", "content", PromptTemplates.load("glean-json-user.txt")));
            int budget = messages.stream()
                            .mapToInt(m -> tokenizer.encode(m.get("content")).length + 8)
                            .sum()
                    + 16
                    + extract.getMaxOutputTokens();
            if (budget <= extract.getContextTokens())
                result = extract(lease, chunk, extract, messages, result, check).finish();
        }
        check.run();
        float[] vector = gateway.embed(embedding, content);
        check.run();
        indexes.checkpoint(lease, chunk, result, vector);
        return result;
    }

    private JsonExtractionStream extract(
            IndexLease lease,
            UUID chunk,
            ModelVersion model,
            List<Map<String, String>> messages,
            Extraction seed,
            Runnable check) {
        var stream = extractOnce(lease, chunk, model, messages, seed, check);
        if (stream.validationFailure() != null) {
            var correctionMessages = new ArrayList<>(messages);
            correctionMessages.add(Map.of("role", "assistant", "content", stream.json()));
            correctionMessages.add(Map.of(
                    "role",
                    "user",
                    "content",
                    PromptTemplates.load("repair-records-json-user.txt")
                            .replace("{validation_code}", stream.validationFailure())));
            // Replace this invalid round completely; never seed it with that round's partial records.
            var corrected = extractOnce(lease, chunk, model, correctionMessages, seed, check);
            corrected.completeCorrection(corrected.finish());
            return corrected;
        }
        if (stream.hasMissingEndpoints()) {
            var correctionMessages = new ArrayList<>(messages);
            correctionMessages.add(Map.of("role", "assistant", "content", stream.json()));
            correctionMessages.add(Map.of("role", "user", "content", PromptTemplates.load("repair-json-user.txt")));
            var correction = extractOnce(lease, chunk, model, correctionMessages, stream.finish(), check);
            correction.completeCorrection(correction.finish());
            stream.completeCorrection(correction.finish());
        }
        return stream;
    }

    private JsonExtractionStream extractOnce(
            IndexLease lease,
            UUID chunk,
            ModelVersion model,
            List<Map<String, String>> messages,
            Extraction seed,
            Runnable check) {
        check.run();
        var stream = new JsonExtractionStream(
                parser,
                seed,
                value -> {
                    check.run();
                    indexes.extractionProgress(lease, chunk, value);
                },
                true);
        indexes.extractionProgress(lease, chunk, seed);
        gateway.streamJson(model, messages, stream::accept, () -> {
            try {
                check.run();
            } catch (com.trs.app.rag.RagAccessException revoked) {
                throw new ModelFailure("index_access_revoked");
            }
        });
        check.run();
        stream.finishForCorrection();
        return stream;
    }
}
