package com.trs.app.rag.ingestion;

import com.trs.app.rag.RagAccessException;
import java.util.concurrent.CancellationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CPU parsing and chunking outside database transactions; fenced publication is atomic.
 *
 * @author agent
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionProcessor {
  private final JobService jobs;
  private final DocumentParser parser;
  private final FixedTokenChunker chunker;
  private final IngestionSettings settings;

  /**
   * Runs parsing and chunking outside database transactions.
   *
   * @author agent
   */
  public void process(JobLease lease) {
    process(lease, () -> false);
  }

  /**
   * Processes one claimed input with a heartbeat-driven cancellation signal.
   *
   * @author agent
   */
  public void process(JobLease lease, java.util.function.BooleanSupplier stopped) {
    try {
      var input = jobs.load(lease);
      var parsed =
          input.getParsedText() == null
              ? parser.parse(input.getOriginal(), input.getFormat())
              : parser.fromText(input.getParsedText(), input.getFormat());
      if (!jobs.heartbeat(lease)) throw new LeaseLostException();
      jobs.checkpoint(lease, parsed.getText());
      var pieces =
          chunker.split(
              parsed.getText(),
              input.getChunkSize(),
              input.getOverlap(),
              settings.getMaxChunks(),
              stopped);
      parser.assignHeadings(parsed, pieces, stopped);
      jobs.complete(lease, pieces);
    } catch (LeaseLostException | CancellationException lost) {
      jobs.settleCancellation(lease);
    } catch (ProcessingFailure failure) {
      jobs.fail(lease, failure.getCode(), false);
    } catch (RagAccessException lost) {
      jobs.fail(lease, "permission_lost", false);
    } catch (RuntimeException failure) {
      log.warn("Document job {} failed with {}", lease.getId(), failure.getClass().getSimpleName());
      jobs.fail(lease, "processing_error", true);
    }
  }
}
