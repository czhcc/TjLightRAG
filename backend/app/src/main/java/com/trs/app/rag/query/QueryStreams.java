package com.trs.app.rag.query;

import com.trs.app.rag.*;
import com.trs.app.rag.model.ModelFailure;
import com.trs.app.rag.retrieval.RetrievalFailure;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bounded session/user-scoped stream lifecycle with explicit cancel and disconnect
 * detection. @author agent
 */
@Component
public class QueryStreams {
  private final QueryService service;
  private final KnowledgeBaseService access;
  private final int timeout;
  private final ConcurrentMap<Key, Run> runs = new ConcurrentHashMap<>();
  private final Semaphore slots = new Semaphore(16);
  private final ScheduledExecutorService clock =
      Executors.newScheduledThreadPool(
          2, Thread.ofPlatform().daemon().name("query-heartbeat-", 0).factory());

  /** Immutable authorization and cancellation scope. @author agent */
  private record Key(UUID kb, String actor, String session, UUID id) {}

  /** One bounded stream lifecycle; never stores model secrets. @author agent */
  private static class Run {
    final SseEmitter emitter;
    final AtomicBoolean terminal = new AtomicBoolean();
    volatile Thread worker;

    Run(SseEmitter emitter) {
      this.emitter = emitter;
    }

    void cancel() {
      terminal.set(true);
      if (worker != null) worker.interrupt();
      emitter.complete();
    }
  }

  /** Uses one overall stream deadline, including retrieval and provider reads. @author agent */
  public QueryStreams(
      QueryService service,
      KnowledgeBaseService access,
      @Value("${rag.query.timeout-seconds:180}") int timeout) {
    this.service = service;
    this.access = access;
    this.timeout = timeout;
  }

  /** Registers a stream only after authorization; no unbounded executor queue. @author agent */
  public SseEmitter start(UUID kb, String actor, String session, QueryRequest request) {
    access.requireAccess(kb, actor, false);
    if (request.getRequestId() == null) throw new IllegalArgumentException();
    if (!slots.tryAcquire()) throw new RetrievalFailure("query_busy", 429);
    var key = new Key(kb, actor, session, request.getRequestId());
    var run = new Run(new SseEmitter((timeout + 5) * 1000L));
    if (runs.putIfAbsent(key, run) != null) {
      slots.release();
      throw new RetrievalFailure("query_request_conflict", 409);
    }
    run.emitter.onCompletion(run::cancel);
    run.emitter.onTimeout(run::cancel);
    run.emitter.onError(e -> run.cancel());
    run.worker =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  var heartbeat =
                      clock.scheduleAtFixedRate(
                          () -> {
                            try {
                              access.requireAccess(kb, actor, false);
                              send(run, "heartbeat", Map.of());
                            } catch (Exception e) {
                              run.cancel();
                            }
                          },
                          2,
                          2,
                          TimeUnit.SECONDS);
                  var deadline =
                      clock.schedule(
                          () -> {
                            if (run.terminal.compareAndSet(false, true)) {
                              try {
                                run.emitter.send(
                                    SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("error", "query_timeout")));
                              } catch (Exception ignored) {
                              }
                              run.worker.interrupt();
                              run.emitter.complete();
                            }
                          },
                          timeout,
                          TimeUnit.SECONDS);
                  try {
                    QueryResult result =
                        service.stream(kb, actor, request, (event, data) -> send(run, event, data));
                    if (!run.terminal.get()) send(run, "done", result);
                  } catch (Exception e) {
                    if (!run.terminal.get()) {
                      String code =
                          e instanceof ModelFailure m
                              ? m.getCode()
                              : e instanceof RetrievalFailure r
                                  ? r.getCode()
                                  : e instanceof RagAccessException
                                      ? "query_access_denied"
                                      : "query_failed";
                      try {
                        send(run, "error", Map.of("error", code));
                      } catch (Exception ignored) {
                      }
                    }
                  } finally {
                    run.terminal.set(true);
                    heartbeat.cancel(false);
                    deadline.cancel(false);
                    runs.remove(key, run);
                    slots.release();
                    run.emitter.complete();
                  }
                });
    run.worker.start();
    return run.emitter;
  }

  private void send(Run run, String event, Object data) {
    if (run.terminal.get() || Thread.currentThread().isInterrupted())
      throw new RetrievalFailure("query_cancelled", 409);
    try {
      run.emitter.send(SseEmitter.event().name(event).data(data));
    } catch (IOException | IllegalStateException e) {
      run.cancel();
      throw new RetrievalFailure("query_cancelled", 409);
    }
  }

  /** Cancels only the matching knowledge base, user, HTTP session and request ID. @author agent */
  public void cancel(UUID kb, String actor, String session, UUID id) {
    access.requireAccess(kb, actor, false);
    var run = runs.get(new Key(kb, actor, session, id));
    if (run != null) run.cancel();
  }

  /** Cancels active model requests on graceful application shutdown. @author agent */
  @PreDestroy
  public void close() {
    runs.values().forEach(Run::cancel);
    clock.shutdownNow();
  }
}
