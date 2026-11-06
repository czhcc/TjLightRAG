package com.trs.app.rag.retrieval;

import com.trs.app.rag.model.ModelVersion;
import com.trs.app.rag.retrieval.RetrievalData.Snapshot;
import java.util.function.Supplier;

/** Explicit parent operation scope for fixed-snapshot agent retrievals. @author czhcc */
public record RetrievalScope(Snapshot snapshot, long deadline, ModelVersion keywordModel, Observer observer) {
    /** Observes only explicit provider protocol calls; no thread-local credential context. @author czhcc */
    public interface Observer {
        /** Wraps a model call for safe request and result capture. @author czhcc */
        <T> T call(String name, ModelVersion model, Object request, Supplier<T> operation);
    }
    /** Calls directly when no parent trace is attached. @author czhcc */
    public <T> T call(String name, ModelVersion model, Object request, Supplier<T> operation) {
        return observer == null ? operation.get() : observer.call(name, model, request, operation);
    }
}
