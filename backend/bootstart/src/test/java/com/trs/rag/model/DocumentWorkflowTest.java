package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.*;
import com.trs.app.rag.index.IndexService;
import com.trs.app.rag.ingestion.DocumentWorkflowService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Document actions must check write membership before any mutation. @author czhcc */
class DocumentWorkflowTest {
  @Test
  void readersCannotPauseResumeRebuildOrDelete() {
    var jdbc = mock(JdbcTemplate.class);
    var access = mock(KnowledgeBaseService.class);
    var workflow = new DocumentWorkflowService(jdbc, access, mock(IndexService.class));
    UUID kb = UUID.randomUUID(), document = UUID.randomUUID();
    when(access.requireAccess(kb, "reader", true)).thenThrow(new RagAccessException(403));
    for (String action : new String[] {"pause", "run", "rebuild", "delete"}) {
      assertEquals(403, assertThrows(RagAccessException.class,
          () -> workflow.action(kb, document, "reader", action)).getStatus());
    }
    verifyNoInteractions(jdbc);
  }

  @Test
  void unknownActionsDoNotMutateDocuments() {
    var jdbc = mock(JdbcTemplate.class);
    var workflow = new DocumentWorkflowService(jdbc, mock(KnowledgeBaseService.class), mock(IndexService.class));
    assertThrows(IllegalArgumentException.class,
        () -> workflow.action(UUID.randomUUID(), UUID.randomUUID(), "writer", "activate"));
    verifyNoInteractions(jdbc);
  }
}
