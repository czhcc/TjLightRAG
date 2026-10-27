package com.trs.app.rag.index;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/** Bounded asynchronous dispatch; the database remains the sole durable queue. @author agent */
@Component
@EnableScheduling
@Slf4j
@ConditionalOnProperty(name = "rag.indexing.enabled", havingValue = "true", matchIfMissing = true)
public class IndexWorker {
  private final IndexProcessor processor;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "rag-index");
            t.setDaemon(true);
            return t;
          });

  /**
   * Creates the single-worker dispatcher; multiple instances coordinate through SKIP
   * LOCKED. @author agent
   */
  public IndexWorker(IndexProcessor processor) {
    this.processor = processor;
  }

  /** Schedules at most one local model pipeline without blocking request threads. @author agent */
  @Scheduled(fixedDelayString = "${rag.indexing.poll-ms:1000}")
  public void poll() {
    if (!busy.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            try {
              processor.runOnce();
            } catch (RuntimeException unavailable) {
              log.warn("Index queue unavailable ({})", unavailable.getClass().getSimpleName());
            } finally {
              busy.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      busy.set(false);
    }
  }

  /** Stops local execution; a replacement instance recovers expired leases. @author agent */
  @PreDestroy
  public void close() {
    worker.shutdownNow();
  }
}
