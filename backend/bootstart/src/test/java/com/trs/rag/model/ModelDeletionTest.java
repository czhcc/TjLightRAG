package com.trs.rag.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trs.app.rag.RagAccessException;
import com.trs.app.rag.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Authorization and immutable version deletion boundaries. @author czhcc */
class ModelDeletionTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ModelConfigurationService service = new ModelConfigurationService(
      jdbc, mock(SecretVault.class), new EndpointPolicy(), mock(ModelGateway.class));

  @Test
  void nonAdministratorsCannotDeleteVersions() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("reader"))).thenReturn(false);
    assertEquals(403, assertThrows(RagAccessException.class,
        () -> service.delete(UUID.randomUUID(), "reader")).getStatus());
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void missingAndLatestVersionsCannotBeDeleted() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("admin"))).thenReturn(true);
    UUID version = UUID.randomUUID(), profile = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), eq(version))).thenReturn(List.of());
    assertEquals(404, assertThrows(RagAccessException.class,
        () -> service.delete(version, "admin")).getStatus());
    when(jdbc.queryForList(anyString(), eq(version)))
        .thenReturn(List.of(Map.of("profile_id", profile, "version", 2)));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(profile))).thenReturn(2);
    assertThrows(DataIntegrityViolationException.class, () -> service.delete(version, "admin"));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void unreferencedOldVersionCanBeDeletedButReferenceConflictsPropagate() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("admin"))).thenReturn(true);
    UUID version = UUID.randomUUID(), profile = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), eq(version)))
        .thenReturn(List.of(Map.of("profile_id", profile, "version", 1)));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(profile))).thenReturn(3);
    when(jdbc.update(anyString(), eq(version))).thenReturn(1)
        .thenThrow(new DataIntegrityViolationException("Referenced"));
    assertDoesNotThrow(() -> service.delete(version, "admin"));
    assertThrows(DataIntegrityViolationException.class, () -> service.delete(version, "admin"));
  }
}
