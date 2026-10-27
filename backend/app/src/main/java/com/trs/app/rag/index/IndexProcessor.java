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
                PromptTemplates.load("extract-user.txt")
                    .replace("{heading_context_block}", section)
                    .replace("{input_text}", content);
            String system = PromptTemplates.load("extract-system.txt");
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", system));
            messages.add(Map.of("role", "user", "content", user));
            String first = gateway.chat(extract, messages);
            result = parser.parse(first);
            check.run();
            if (lease.getGleaning() > 0) {
              messages.add(Map.of("role", "assistant", "content", first));
              messages.add(
                  Map.of(
                      "role",
                      "user",
                      "content",
                      PromptTemplates.load("glean-user.txt").replace("{input_text}", content)));
              int budget =
                  messages.stream()
                          .mapToInt(m -> tokenizer.encode(m.get("content")).length + 8)
                          .sum()
                      + 16
                      + extract.getMaxOutputTokens();
              if (budget <= extract.getContextTokens()) {
                var extra = parser.parse(gateway.chat(extract, messages));
                result = parser.glean(result, extra);
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
}
