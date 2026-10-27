package com.trs.app.rag.ingestion;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/**
 * Bounded workers with a database queue; processes never rely on a JVM-only task list.
 *
 * @author agent
 */
@Component
@EnableScheduling
@Slf4j
@ConditionalOnProperty(name = "rag.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionWorker {
  private final JobService jobs;
  private final IngestionProcessor processor;
  private final IngestionSettings settings;
  private final Semaphore slots;
  private final ExecutorService workers;
  private final ScheduledExecutorService heartbeats;
  private final String owner = UUID.randomUUID().toString();
  private volatile boolean stopping;

  /**
   * Initializes IngestionWorker for the document pipeline.
   *
   * @author agent
   */
  public IngestionWorker(
      JobService jobs, IngestionProcessor processor, IngestionSettings settings) {
    this.jobs = jobs;
    this.processor = processor;
    this.settings = settings;
    slots = new Semaphore(settings.getConcurrency());
    workers =
        new ThreadPoolExecutor(
            settings.getConcurrency(),
            settings.getConcurrency(),
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(settings.getConcurrency()),
            r -> {
              var t = new Thread(r, "rag-ingestion");
              t.setDaemon(true);
              return t;
            });
    heartbeats =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "rag-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  @Scheduled(fixedDelayString = "${rag.ingestion.poll-ms:500}")
  /**
   * Dispatches at most the configured number of durable jobs.
   *
   * @author agent
   */
  public void poll() {
    if (stopping) return;
    while (slots.tryAcquire()) {
      JobLease lease;
      try {
        var next = jobs.claim(owner);
        if (next.isEmpty()) {
          slots.release();
          return;
        }
        lease = next.get();
      } catch (RuntimeException unavailable) {
        slots.release();
        log.warn("Ingestion queue unavailable ({})", unavailable.getClass().getSimpleName());
        return;
      }
      try {
        workers.execute(
            () -> {
              var stopped = new AtomicBoolean();
              var pulse =
                  heartbeats.scheduleAtFixedRate(
                      () -> {
                        try {
                          if (!jobs.heartbeat(lease)) stopped.set(true);
                        } catch (RuntimeException unavailable) {
                          log.warn("Job heartbeat unavailable for {}", lease.getId());
                        }
                      },
                      Math.max(1, Math.min(2, settings.getLeaseSeconds() / 3)),
                      Math.max(1, Math.min(2, settings.getLeaseSeconds() / 3)),
                      TimeUnit.SECONDS);
              try {
                processor.process(lease, stopped::get);
              } finally {
                pulse.cancel(false);
                slots.release();
              }
            });
      } catch (RejectedExecutionException stopped) {
        slots.release();
        jobs.fail(lease, "worker_stopped", true);
        return;
      }
    }
  }

  /**
   * Stops local executors; durable leases are recovered by a replacement worker.
   *
   * @author agent
   */
  @PreDestroy
  public void close() {
    stopping = true;
    workers.shutdownNow();
    heartbeats.shutdownNow();
  }
}
