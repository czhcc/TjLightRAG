package com.trs.app.rag.query;

import com.trs.app.rag.retrieval.RetrievalFailure;
import java.util.*;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Coordinates inference outside history transactions and persists terminal states. @author czhcc */
@Service
@RequiredArgsConstructor
public class ConversationQueries {
  private final QueryService service;
  private final ConversationService store;
  private com.trs.app.rag.agent.AgentTraceRecorder traces;
  /** Keeps trace completion consistent with the persisted turn, including lease expiry. @author czhcc */
  @org.springframework.beans.factory.annotation.Autowired
  public void traces(com.trs.app.rag.agent.AgentTraceRecorder traces) { this.traces = traces; }

  /** Previews use saved history without inserting a question or answer. @author czhcc */
  public QueryResult query(UUID kb, String actor, QueryRequest request) {
    return execute(kb, actor, request, null);
  }

  /** Forwards real supplier deltas and saves success before the SSE completion event. @author czhcc */
  public QueryResult stream(UUID kb, String actor, QueryRequest request, BiConsumer<String, Object> events) {
    return execute(kb, actor, request, events);
  }

  private QueryResult execute(UUID kb, String actor, QueryRequest request, BiConsumer<String, Object> events) {
    UUID conversation = request.getConversationId();
    if (conversation == null) return events == null ? service.query(kb, actor, request) : service.stream(kb, actor, request, events);
    if (!request.getPreview().equals("answer")) return service.query(kb, actor, request, store.history(kb, conversation, actor));
    var turn = store.begin(kb, conversation, actor, request.getRequestId(), request.getQuery());
    StringBuilder partial = new StringBuilder();
    try {
      var result = events == null ? service.query(kb, actor, request, turn.history())
          : service.stream(kb, actor, request, turn.history(), (event, value) -> {
            if (event.equals("delta")) partial.append(((Map<?, ?>) value).get("text"));
            events.accept(event, value);
          });
      store.complete(kb, conversation, actor, turn.id(), result);
      if (traces != null) traces.terminal(kb, conversation, turn.id(), actor, "success");
      return result;
    } catch (RuntimeException failure) {
      boolean interrupted = Thread.interrupted();
      boolean cancelled = interrupted || failure instanceof RetrievalFailure r && r.getCode().equals("query_cancelled");
      try {
        store.fail(kb, conversation, actor, turn.id(), partial.toString(), cancelled);
        if (traces != null) traces.terminal(kb, conversation, turn.id(), actor, cancelled ? "cancelled" : "failed");
      }
      catch (RuntimeException ignored) { /* Preserve the original safe failure; stale rows expire. */ }
      finally { if (interrupted) Thread.currentThread().interrupt(); }
      throw failure;
    }
  }
}
