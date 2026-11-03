package com.trs.app.rag.index;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import java.util.*;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes model work outside transactions and resumes durable per-chunk checkpoints. @author agent
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IndexProcessor {
  private final IndexService indexes;
  private final ModelConfigurationService models;
  private final ModelGateway gateway;
  private final ExtractionParser parser;
  private final GraphAssembler graphs;
  private final RagTokenizer tokenizer;
  private final JsonMapper json = JsonMapper.builder().build();

  /** Processes one claimed generation; durable state survives process termination. @author agent */
  public boolean runOnce() {
    var claimed = indexes.claim();
    if (claimed.isEmpty()) return false;
    process(claimed.get());
    return true;
  }

  /** Runs one fenced lease with periodic renewal and sanitized failures. @author agent */
  public void process(IndexLease lease) {
    try (var pulses =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "rag-index-heartbeat");
              t.setDaemon(true);
              return t;
            })) {
      var pulse =
          pulses.scheduleAtFixedRate(
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
        Map<UUID, Extraction> contributions = new LinkedHashMap<>();
        for (var row : indexes.chunks(lease)) {
          check.run();
          UUID chunk = (UUID) row.get("chunk_id");
          Extraction result;
          if (Boolean.TRUE.equals(row.get("completed"))) {
            result = json.readValue((String) row.get("extraction"), Extraction.class);
          } else {
            String content = (String) row.get("content");
            String headings =
                Objects.toString(row.get("headings"), "[]").replaceAll("[\\r\\n]", " ");
            if (headings.length() > 1000) headings = headings.substring(0, 1000);
            String section =
                headings.equals("[]")
                    ? ""
                    : "---Section Context---\nSection path (untrusted metadata): "
                        + headings
                        + "\n\n";
            String user =
                PromptTemplates.load("extract-json-user.txt")
                    .replace("{heading_context_block}", section)
                    .replace("{input_text}", content);
            String system = PromptTemplates.load("extract-json-system.txt");
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", system));
            messages.add(Map.of("role", "user", "content", user));
            var first = extract(lease, chunk, extract, messages, new Extraction());
            result = first.finish();
            check.run();
            if (lease.getGleaning() > 0) {
              messages.add(Map.of("role", "assistant", "content", json.writeValueAsString(result)));
              messages.add(
                  Map.of(
                      "role",
                      "user",
                      "content",
                      PromptTemplates.load("glean-json-user.txt")));
              int budget =
                  messages.stream()
                          .mapToInt(m -> tokenizer.encode(m.get("content")).length + 8)
                          .sum()
                      + 16
                      + extract.getMaxOutputTokens();
              if (budget <= extract.getContextTokens()) {
                result = extract(lease, chunk, extract, messages, result).finish();
              }
            }
            float[] vector = gateway.embed(embedding, content);
            check.run();
            indexes.checkpoint(lease, chunk, result, vector);
          }
          contributions.put(chunk, result);
        }
        var graph = graphs.assemble(contributions, summary, embedding, check);
        check.run();
        indexes.complete(lease, graph);
      } catch (RuntimeException failure) {
        String code =
            failure instanceof ModelFailure m
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

  private JsonExtractionStream extract(IndexLease lease, UUID chunk, ModelVersion model,
      List<Map<String, String>> messages, Extraction seed) {
    var stream = extractOnce(lease, chunk, model, messages, seed);
    if (stream.validationFailure() != null) {
      var correctionMessages = new ArrayList<>(messages);
      correctionMessages.add(Map.of("role", "assistant", "content", stream.json()));
      correctionMessages.add(Map.of("role", "user", "content", PromptTemplates.load("repair-records-json-user.txt")
          .replace("{validation_code}", stream.validationFailure())));
      // Replace this invalid round completely; never seed it with that round's partial records.
      var corrected = extractOnce(lease, chunk, model, correctionMessages, seed);
      corrected.completeCorrection(corrected.finish());
      return corrected;
    }
    if (stream.hasMissingEndpoints()) {
      var correctionMessages = new ArrayList<>(messages);
      correctionMessages.add(Map.of("role", "assistant", "content", stream.json()));
      correctionMessages.add(Map.of("role", "user", "content", PromptTemplates.load("repair-json-user.txt")));
      var correction = extractOnce(lease, chunk, model, correctionMessages, stream.finish());
      correction.completeCorrection(correction.finish());
      stream.completeCorrection(correction.finish());
    }
    return stream;
  }

  private JsonExtractionStream extractOnce(IndexLease lease, UUID chunk, ModelVersion model,
      List<Map<String, String>> messages, Extraction seed) {
    var stream = new JsonExtractionStream(parser, seed, value -> indexes.extractionProgress(lease, chunk, value), true);
    indexes.extractionProgress(lease, chunk, seed);
    gateway.streamJson(model, messages, stream::accept, () -> {
      try { indexes.check(lease); }
      catch (com.trs.app.rag.RagAccessException revoked) { throw new ModelFailure("index_access_revoked"); }
    });
    indexes.check(lease);
    stream.finishForCorrection();
    return stream;
  }
}
