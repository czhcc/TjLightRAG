package com.trs.rag.model;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.trs.app.rag.*;
import com.trs.app.rag.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
/** Current model deletion authorization and binding conflicts. @author czhcc */
class ModelDeletionTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final ModelConfigurationService service = new ModelConfigurationService(jdbc, mock(SecretVault.class),
      new EndpointPolicy(), mock(ModelGateway.class), mock(KnowledgeBaseService.class));
  @Test void nonAdministratorsCannotDeleteModels() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("reader"))).thenReturn(false);
    assertEquals(403, assertThrows(RagAccessException.class, () -> service.delete(UUID.randomUUID(), "reader")).getStatus());
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }
  @Test void missingModelsReturn404AndBindingsPreventDeletion() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("admin"))).thenReturn(true);
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), eq(id))).thenReturn(0).thenThrow(new DataIntegrityViolationException("Bound"));
    assertEquals(404, assertThrows(RagAccessException.class, () -> service.delete(id, "admin")).getStatus());
    assertThrows(DataIntegrityViolationException.class, () -> service.delete(id, "admin"));
  }
}