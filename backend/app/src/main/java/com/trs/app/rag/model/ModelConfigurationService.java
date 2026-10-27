package com.trs.app.rag.model;

import com.trs.app.rag.RagAccessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned administrator model configuration and purpose bindings. @author agent */
@Service
@RequiredArgsConstructor
public class ModelConfigurationService {
  private final JdbcTemplate jdbc;
  private final SecretVault vault;
  private final EndpointPolicy policy;
  private final ModelGateway gateway;
  public static final Set<String> ROLES =
      Set.of("extract", "summary", "keywords", "answer", "embedding", "rerank");

  /** Rechecks the database administrator flag on every privileged operation. @author agent */
  public void admin(String actor) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM app_user WHERE username=? AND is_admin)",
            Boolean.class,
            actor))) throw new RagAccessException(403);
  }

  /** Returns only masked latest profile versions. @author agent */
  public List<Map<String, Object>> list(String actor) {
    admin(actor);
    return jdbc.query(
        "SELECT v.*,p.name,p.kind FROM rag_model_profile p JOIN rag_model_version v ON"
            + " v.profile_id=p.id ORDER BY p.created_at DESC,p.id,v.version DESC LIMIT 500",
        (rs, n) -> view(rs));
  }

  /** Reads masked immutable version history for a profile. @author agent */
  public List<Map<String, Object>> history(UUID profile, String actor) {
    admin(actor);
    return jdbc.query(
        "SELECT v.*,p.name,p.kind FROM rag_model_profile p JOIN rag_model_version v ON"
            + " v.profile_id=p.id WHERE p.id=? ORDER BY v.version DESC",
        (rs, n) -> view(rs),
        profile);
  }

  private Map<String, Object> view(java.sql.ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", rs.getObject("id"));
    m.put("profileId", rs.getObject("profile_id"));
    m.put("name", rs.getString("name"));
    m.put("kind", rs.getString("kind"));
    m.put("version", rs.getInt("version"));
    m.put("baseUrl", rs.getString("base_url"));
    m.put("model", rs.getString("model"));
    m.put("keyConfigured", !rs.getString("secret_cipher").isEmpty());
    m.put("dimensions", rs.getInt("dimensions"));
    m.put("contextTokens", rs.getInt("context_tokens"));
    m.put("maxOutputTokens", rs.getInt("max_output_tokens"));
    m.put("timeoutSeconds", rs.getInt("timeout_seconds"));
    return m;
  }

  /**
   * Appends an immutable version; an empty key preserves the previous encrypted key. @author agent
   */
  @Transactional
  public Map<String, Object> save(UUID profile, ModelRequest r, String actor) {
    admin(actor);
    validate(r);
    policy.validate(r.getBaseUrl());
    int version = 1;
    String cipher = "";
    if (profile == null) {
      profile = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO rag_model_profile(id,name,kind) VALUES (?,?,?)",
          profile,
          r.getName().strip(),
          r.getKind());
    } else {
      var rows =
          jdbc.queryForList("SELECT kind FROM rag_model_profile WHERE id=? FOR UPDATE", profile);
      if (rows.isEmpty()) throw new RagAccessException(404);
      if (!rows.getFirst().get("kind").equals(r.getKind()))
        throw new IllegalArgumentException("Cannot change model kind");
      jdbc.update("UPDATE rag_model_profile SET name=? WHERE id=?", r.getName().strip(), profile);
      var old =
          jdbc.queryForMap(
              "SELECT version,secret_cipher FROM rag_model_version WHERE profile_id=? ORDER BY"
                  + " version DESC LIMIT 1",
              profile);
      version = ((Number) old.get("version")).intValue() + 1;
      cipher = (String) old.get("secret_cipher");
    }
    if (r.isClearApiKey()) cipher = "";
    else if (r.getApiKey() != null && !r.getApiKey().isBlank())
      cipher = vault.encrypt(r.getApiKey(), profile);
    UUID id = UUID.randomUUID();
    String url = r.getBaseUrl().replaceAll("/+$", "");
    jdbc.update(
        "INSERT INTO"
            + " rag_model_version(id,profile_id,version,base_url,model,secret_cipher,dimensions,context_tokens,max_output_tokens,timeout_seconds)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        profile,
        version,
        url,
        r.getModel().strip(),
        cipher,
        r.getDimensions(),
        r.getContextTokens(),
        r.getMaxOutputTokens(),
        r.getTimeoutSeconds());
    return history(profile, actor).getFirst();
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
  }

  /** Resolves a captured version without exposing its secret through REST. @author agent */
  public ModelVersion version(UUID id) {
    var rows =
        jdbc.query(
            "SELECT v.*,p.kind FROM rag_model_version v JOIN rag_model_profile p ON"
                + " p.id=v.profile_id WHERE v.id=?",
            (rs, n) ->
                new ModelVersion(
                    id,
                    rs.getObject("profile_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("base_url"),
                    rs.getString("model"),
                    rs.getString("secret_cipher"),
                    rs.getInt("dimensions"),
                    rs.getInt("context_tokens"),
                    rs.getInt("max_output_tokens"),
                    rs.getInt("timeout_seconds")),
            id);
    if (rows.isEmpty()) throw new RagAccessException(404);
    return rows.getFirst();
  }

  /** Reads global role bindings for an administrator. @author agent */
  public Map<String, UUID> bindings(String actor) {
    admin(actor);
    return snapshot();
  }

  /** Captures immutable binding IDs for one new generation. @author agent */
  public Map<String, UUID> snapshot() {
    Map<String, UUID> result = new LinkedHashMap<>();
    jdbc.query(
        "SELECT role,version_id FROM rag_model_binding ORDER BY role",
        rs -> {
          result.put(rs.getString("role"), rs.getObject("version_id", UUID.class));
        });
    return result;
  }

  /**
   * Atomically updates selected role bindings, without changing existing generations. @author agent
   */
  @Transactional
  public void bind(Map<String, UUID> values, String actor) {
    admin(actor);
    if (values == null || values.size() > 6) throw new IllegalArgumentException();
    for (var e : values.entrySet()) {
      if (!ROLES.contains(e.getKey())) throw new IllegalArgumentException();
      if (e.getValue() == null) {
        jdbc.update("DELETE FROM rag_model_binding WHERE role=?", e.getKey());
        continue;
      }
      var v = version(e.getValue());
      String kind =
          e.getKey().equals("embedding")
              ? "EMBEDDING"
              : e.getKey().equals("rerank") ? "RERANK" : "CHAT";
      if (!v.getKind().equals(kind))
        throw new IllegalArgumentException("Incompatible model purpose");
      jdbc.update(
          "INSERT INTO rag_model_binding(role,version_id) VALUES (?,?) ON CONFLICT (role) DO UPDATE"
              + " SET version_id=EXCLUDED.version_id",
          e.getKey(),
          e.getValue());
    }
  }

  /**
   * Makes a bounded wire-protocol connectivity probe; never returns provider payloads. @author
   * agent
   */
  public Map<String, Object> test(UUID id, String actor) {
    admin(actor);
    var v = version(id);
    long begin = System.nanoTime();
    switch (v.getKind()) {
      case "CHAT" -> gateway.chat(v, List.of(Map.of("role", "user", "content", "Reply with OK.")));
      case "EMBEDDING" -> gateway.embed(v, "connectivity test");
      case "RERANK" -> gateway.rerank(v, "test", List.of("test"));
      default -> throw new IllegalArgumentException();
    }
    return Map.of(
        "success", true, "elapsedMs", (System.nanoTime() - begin) / 1000000, "kind", v.getKind());
  }

  /** Reports nonsecret model deployment prerequisites. @author agent */
  public Map<String, Object> status(String actor) {
    admin(actor);
    return Map.of(
        "masterKeyConfigured",
        vault.configured(),
        "protocol",
        "openai-compatible",
        "tokenizer",
        "cl100k_base");
  }
}
