package com.trs.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.ingestion.RagTokenizer;
import com.trs.app.rag.model.*;
import com.trs.app.rag.retrieval.*;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Connection acquisition failures are not SQL exceptions and still need a safe unavailable
 * response. @author agent
 */
class RetrievalFailureTest {
  @Test
  void connectionFailureIsUnavailableReleasesAdmissionAndDoesNotLeakDetails() {
    var repository = mock(RetrievalRepository.class);
    var models = mock(ModelConfigurationService.class);
    var gateway = mock(ModelGateway.class);
    var tokenizer = mock(RagTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(new int[] {1});
    UUID kb = UUID.randomUUID();
    when(repository.capture(eq(kb), eq("admin")))
        .thenThrow(new CannotCreateTransactionException("private connection details"));
    when(repository.source(any(), any(), any(), any(), any(), anyInt(), anyString()))
        .thenThrow(new CannotCreateTransactionException("private connection details"));
    var algorithms = new RetrievalAlgorithms();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var service =
          new RetrievalService(
              repository,
              models,
              gateway,
              tokenizer,
              algorithms,
              new ContextPlanner(tokenizer, algorithms),
              factory.getValidator(),
              1,
              120);
      var request = new RetrievalRequest();
      request.setQuery("question");
      for (int attempt = 0; attempt < 2; attempt++) {
        var failure =
            assertThrows(RetrievalFailure.class, () -> service.query(kb, "admin", request));
        assertEquals(503, failure.getStatus());
        assertEquals("retrieval_storage_unavailable", failure.getCode());
        assertNull(failure.getCause());
      }
      var failure =
          assertThrows(
              RetrievalFailure.class,
              () ->
                  service.source(
                      kb,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      0,
                      "admin"));
      assertEquals(503, failure.getStatus());
      verifyNoInteractions(gateway);
    }
  }
}
