package com.trs.app.rag.model;

import com.trs.app.rag.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Editable model catalog and knowledge-base purpose settings. @author czhcc */
@Service
@RequiredArgsConstructor
public class ModelConfigurationService {
  private final JdbcTemplate jdbc;
  private final SecretVault vault;
  private final EndpointPolicy policy;
  private final ModelGateway gateway;
  private final KnowledgeBaseService access;
  private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
  public static final Set<String> ROLES = Set.of("extract", "summary", "keywords", "answer", "embedding", "rerank");

  /** Checks the current platform administrator flag. @author czhcc */
  public void admin(String actor) {
    if (!Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM app_user WHERE username=? AND is_admin)", Boolean.class, actor)))
      throw new RagAccessException(403);
  }

  /** Lists one masked row per editable model, without configuration history. @author czhcc */
  public List<Map<String, Object>> list(String actor) {
    admin(actor);
    return jdbc.query("SELECT c.*,p.name,p.kind FROM rag_model_configuration c JOIN rag_model_profile p ON p.id=c.id ORDER BY p.created_at DESC,p.id",
      (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id")); m.put("profileId", rs.getObject("id"));
        for (String key : List.of("name", "kind", "model")) m.put(key, rs.getString(key));
        m.put("baseUrl", rs.getString("base_url"));
        m.put("keyConfigured", !rs.getString("secret_cipher").isEmpty());
        m.put("dimensions", rs.getInt("dimensions")); m.put("contextTokens", rs.getInt("context_tokens"));
        m.put("maxOutputTokens", rs.getInt("max_output_tokens")); m.put("timeoutSeconds", rs.getInt("timeout_seconds"));
        m.put("extraBody", ModelExtraParameters.read(rs.getString("extra_body")));
        return m;
      });
  }

  /** Creates or directly updates current settings, preserving a blank submitted key. @author czhcc */
  @Transactional
  public Map<String, Object> save(UUID profile, ModelRequest r, String actor) {
    admin(actor); validate(r); policy.validate(r.getBaseUrl());
    String cipher = "";
    if (profile == null) {
      profile = UUID.randomUUID();
      jdbc.update("INSERT INTO rag_model_profile(id,name,kind) VALUES (?,?,?)", profile, r.getName().strip(), r.getKind());
    } else {
      var rows = jdbc.queryForList("SELECT p.kind,c.secret_cipher FROM rag_model_profile p JOIN rag_model_configuration c ON c.id=p.id WHERE p.id=? FOR UPDATE OF p", profile);
      if (rows.isEmpty()) throw new RagAccessException(404);
      if (!r.getKind().equals(rows.getFirst().get("kind"))) throw new IllegalArgumentException("Cannot change model kind");
      cipher = (String) rows.getFirst().get("secret_cipher");
      jdbc.update("UPDATE rag_model_profile SET name=? WHERE id=?", r.getName().strip(), profile);
    }
    if (r.isClearApiKey()) cipher = "";
    else if (r.getApiKey() != null && !r.getApiKey().isBlank()) cipher = vault.encrypt(r.getApiKey(), profile);
    jdbc.update("INSERT INTO rag_model_configuration(id,base_url,model,secret_cipher,dimensions,context_tokens,max_output_tokens,timeout_seconds,extra_body)"
      + " VALUES (?,?,?,?,?,?,?,?,?::JSONB) ON CONFLICT (id) DO UPDATE SET base_url=EXCLUDED.base_url,model=EXCLUDED.model,"
      + " secret_cipher=EXCLUDED.secret_cipher,dimensions=EXCLUDED.dimensions,context_tokens=EXCLUDED.context_tokens,"
      + " max_output_tokens=EXCLUDED.max_output_tokens,timeout_seconds=EXCLUDED.timeout_seconds,extra_body=EXCLUDED.extra_body",
      profile, r.getBaseUrl().replaceAll("/+$", ""), r.getModel().strip(), cipher, r.getDimensions(), r.getContextTokens(),
      r.getMaxOutputTokens(), r.getTimeoutSeconds(), json.writeValueAsString(ModelExtraParameters.copy(r.getExtraBody())));
    UUID saved = profile;
    return list(actor).stream().filter(m -> saved.equals(m.get("id"))).findFirst().orElseThrow();
  }

  private void validate(ModelRequest r) {
    if (r == null
        || r.getName() == null
        || r.getName().isBlank()
        || r.getName().length() > 100
        || !Set.of("CHAT", "EMBEDDING", "RERANK").contains(Objects.toString(r.getKind(), ""))
        || r.getBaseUrl() == null
        || r.getModel() == null
        || r.getModel().isBlank()
        || r.getModel().length() > 256
        || r.getModel().chars().anyMatch(Character::isISOControl)
        || r.getApiKey() != null && r.getApiKey().length() > 4096
        || r.getDimensions() < 0
        || r.getDimensions() > 2000
        || ("EMBEDDING".equals(r.getKind()) ? r.getDimensions() == 0 : r.getDimensions() != 0)
        || r.getContextTokens() < 1024
        || r.getContextTokens() > 1000000
        || r.getMaxOutputTokens() < 64
        || r.getMaxOutputTokens() > 32000
        || r.getMaxOutputTokens() >= r.getContextTokens()
        || r.getTimeoutSeconds() < 1
        || r.getTimeoutSeconds() > 120)
      throw new IllegalArgumentException("Invalid model configuration");
    var extra = ModelExtraParameters.copy(r.getExtraBody());
    if (!"CHAT".equals(r.getKind()) && !extra.isEmpty())
      throw new IllegalArgumentException("Extensions require a text model");
  }

  /** Deletes an unbound current model; immutable index inputs remain available. @author czhcc */
  @Transactional
  public void delete(UUID id, String actor) {
    admin(actor);
    if (jdbc.update("DELETE FROM rag_model_profile WHERE id=?", id) == 0) throw new RagAccessException(404);
  }

  /** Reads a captured index model or a current configuration only inside the server. @author czhcc */
  public ModelVersion version(UUID id) {
    var rows = jdbc.query("SELECT c.*,c.id AS profile_id,p.kind FROM rag_model_configuration c JOIN rag_model_profile p ON p.id=c.id WHERE c.id=?", (rs, n) -> model(rs), id);
    if (rows.isEmpty()) rows = jdbc.query("SELECT * FROM rag_index_model_snapshot WHERE id=?", (rs, n) -> model(rs), id);
    if (rows.isEmpty()) throw new RagAccessException(404);
    return rows.getFirst();
  }

  private ModelVersion model(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ModelVersion(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class), rs.getString("kind"),
      rs.getString("base_url"), rs.getString("model"), rs.getString("secret_cipher"), rs.getInt("dimensions"),
      rs.getInt("context_tokens"), rs.getInt("max_output_tokens"), rs.getInt("timeout_seconds"), ModelExtraParameters.read(rs.getString("extra_body")));
  }

  /** Returns current purpose IDs for one knowledge base, with no global fallback. @author czhcc */
  public Map<String, UUID> snapshot(UUID kb) {
    Map<String, UUID> result = new LinkedHashMap<>();
    jdbc.query("SELECT role,model_id FROM rag_knowledge_base_model_binding WHERE knowledge_base_id=? ORDER BY role",
      rs -> { result.put(rs.getString("role"), rs.getObject("model_id", UUID.class)); }, kb);
    return result;
  }

  /** Captures immutable execution parameters only when building an index. @author czhcc */
  @Transactional
  public Map<String, UUID> captureIndexBindings(UUID kb) {
    Map<String, UUID> current = snapshot(kb), captured = new LinkedHashMap<>();
    var ids = current.entrySet().stream().filter(e -> Set.of("extract", "summary", "embedding").contains(e.getKey()))
      .map(Map.Entry::getValue).distinct().sorted().toList();
    for (UUID id : ids) {
      jdbc.queryForList("SELECT id FROM rag_model_profile WHERE id=? FOR UPDATE", id);
      var v = version(id);
      String extra = json.writeValueAsString(v.getExtraBody());
      var existing = jdbc.queryForList("SELECT id FROM rag_index_model_snapshot WHERE profile_id=? AND kind=? AND base_url=?"
        + " AND model=? AND secret_cipher=? AND dimensions=? AND context_tokens=? AND max_output_tokens=? AND timeout_seconds=?"
        + " AND extra_body=?::JSONB ORDER BY created_at,id LIMIT 1", UUID.class, id, v.getKind(), v.getBaseUrl(), v.getModel(),
        v.getCipher(), v.getDimensions(), v.getContextTokens(), v.getMaxOutputTokens(), v.getTimeoutSeconds(), extra);
      UUID frozen = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
      if (existing.isEmpty()) jdbc.update("INSERT INTO rag_index_model_snapshot(id,profile_id,kind,base_url,model,secret_cipher,dimensions,context_tokens,max_output_tokens,timeout_seconds,extra_body) VALUES (?,?,?,?,?,?,?,?,?,?,?::JSONB)",
        frozen, id, v.getKind(), v.getBaseUrl(), v.getModel(), v.getCipher(), v.getDimensions(), v.getContextTokens(), v.getMaxOutputTokens(), v.getTimeoutSeconds(), extra);
      for (var e : current.entrySet()) if (e.getValue().equals(id) && Set.of("extract", "summary", "embedding").contains(e.getKey())) captured.put(e.getKey(), frozen);
    }
    return captured;
  }

  /** Reads bindings after checking knowledge-base membership. @author czhcc */
  public Map<String, UUID> bindings(UUID kb, String actor) {
    access.requireAccess(kb, actor, false); return snapshot(kb);
  }

  /** Offers only names and types to members selecting models, without credentials. @author czhcc */
  public List<Map<String, Object>> options(UUID kb, String actor) {
    access.requireAccess(kb, actor, false);
    return jdbc.queryForList("SELECT p.id,p.name,p.kind FROM rag_model_profile p ORDER BY p.name,p.id");
  }

  /** Updates this knowledge base's uses; only its owner can change shared execution policy. @author czhcc */
  @Transactional
  public void bind(UUID kb, Map<String, UUID> values, String actor) {
    if (access.requireAccess(kb, actor, false) != KnowledgeRole.OWNER) throw new RagAccessException(403);
    jdbc.queryForList("SELECT id FROM rag_knowledge_base WHERE id=? FOR UPDATE", kb);
    if (values == null || values.size() > 6) throw new IllegalArgumentException();
    for (var e : values.entrySet()) {
      if (!ROLES.contains(e.getKey())) throw new IllegalArgumentException();
      if (e.getValue() == null) {
        jdbc.update("DELETE FROM rag_knowledge_base_model_binding WHERE knowledge_base_id=? AND role=?", kb, e.getKey());
      } else {
        var v = version(e.getValue());
        String kind = e.getKey().equals("embedding") ? "EMBEDDING" : e.getKey().equals("rerank") ? "RERANK" : "CHAT";
        if (!kind.equals(v.getKind())) throw new IllegalArgumentException("Incompatible model purpose");
        jdbc.update("INSERT INTO rag_knowledge_base_model_binding(knowledge_base_id,role,model_id) VALUES (?,?,?)"
          + " ON CONFLICT (knowledge_base_id,role) DO UPDATE SET model_id=EXCLUDED.model_id", kb, e.getKey(), e.getValue());
      }
    }
  }

  /** Tests the current configuration with the supplier without returning provider payloads. @author czhcc */
  public Map<String, Object> test(UUID id, String actor) {
    admin(actor); var v = version(id); long begin = System.nanoTime();
    switch (v.getKind()) {
      case "CHAT" -> gateway.chat(v, List.of(Map.of("role", "user", "content", "Reply with OK.")));
      case "EMBEDDING" -> gateway.embed(v, "connectivity test");
      case "RERANK" -> gateway.rerank(v, "test", List.of("test"));
      default -> throw new IllegalArgumentException();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("success", true); result.put("kind", v.getKind());
    if (v.getKind().equals("CHAT")) result.put("toolCallingSupported", gateway.supportsTools(v));
    result.put("elapsedMs", (System.nanoTime() - begin) / 1000000); return result;
  }

  /** Reports nonsecret deployment readiness. @author czhcc */
  public Map<String, Object> status(String actor) {
    admin(actor); return Map.of("masterKeyConfigured", vault.configured(), "protocol", "openai-compatible", "tokenizer", "cl100k_base");
  }
}
