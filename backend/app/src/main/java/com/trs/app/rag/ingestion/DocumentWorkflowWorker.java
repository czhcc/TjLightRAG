package com.trs.app.rag.ingestion;

import com.trs.app.rag.model.ModelFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/** Polls durable document workflows; model execution remains in the index worker. @author czhcc */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="rag.indexing.enabled", havingValue="true", matchIfMissing=true)
public class DocumentWorkflowWorker {
  private final DocumentWorkflowService workflow;

  /** Coordinates bounded knowledge-base transitions and preserves sanitized failures. @author czhcc */
  @Scheduled(fixedDelayString="${rag.indexing.poll-ms:1000}")
  public void poll() {
    for (var item : workflow.pending()) {
      var kb = (java.util.UUID) item.get("knowledge_base_id");
      long revision = ((Number) item.get("revision")).longValue();
      try { workflow.advance(kb, revision); }
      catch (RuntimeException error) {
        String code = error instanceof ModelFailure ? error.getMessage() : "document_pipeline_failed";
        workflow.failed(kb, revision, (java.util.UUID) item.get("generation_id"), code);
        log.warn("Document workflow {} stopped ({})", kb, code);
      }
    }
  }
}
