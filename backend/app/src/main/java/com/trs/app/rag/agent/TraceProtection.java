package com.trs.app.rag.agent;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Redacts structured protocol data and encrypts it using a purpose-separated key. @author czhcc */
@Component
public class TraceProtection {
  private final byte[] key;
  private final JsonMapper json = JsonMapper.builder().build();
  /** Derives a trace-only AES key; credentials themselves are never used as trace keys. @author czhcc */
  public TraceProtection(@Value("${rag.models.master-key:}") String master) {
    try {
      if (master.isBlank()) { key = null; return; }
      byte[] raw = Base64.getDecoder().decode(master);
      if (raw.length != 32) throw new IllegalArgumentException();
      var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(raw, "HmacSHA256"));
      key = mac.doFinal("TjLightRAG/agent-trace/v1".getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) { throw new IllegalArgumentException("Invalid trace master key"); }
  }
  /** Removes known credential and hidden reasoning fields recursively. @author czhcc */
  public String sanitize(Object value) { return json.writeValueAsString(clean(value)); }
  private Object clean(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> safe = new LinkedHashMap<>();
      map.forEach((k, v) -> {
        String name = k.toString(), normalized = name.replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("reasoning") && !normalized.equals("reasoningeffort")) return;
        if (normalized.equals("token") || normalized.matches(".*(apikey|authorization|password|secret|cipher|cookie|csrf|session|lease|accesstoken|refreshtoken).*")) safe.put(name, "[REDACTED]");
        else safe.put(name, clean(v));
      }); return safe;
    }
    if (value instanceof Collection<?> list) return list.stream().map(this::clean).toList();
    if (value instanceof String s) {
      if (s.startsWith("{") || s.startsWith("[")) {
        try { return json.writeValueAsString(clean(json.readValue(s, Object.class))); } catch (RuntimeException ignored) { /* Ordinary model text. */ }
      }
      return s.replaceAll("(https?://)[^/@\\s]+:[^/@\\s]+@", "$1[REDACTED]@")
          .replaceAll("(?i)([?&](?:token|key|api_key|password|secret)=)[^&\\s]*", "$1[REDACTED]");
    }
    if (value == null || value instanceof Number || value instanceof Boolean) return value;
    return clean(json.convertValue(value, Object.class));
  }
  /** Encrypts one segment with its immutable ID as authenticated data. @author czhcc */
  public String encrypt(String text, UUID id) { return crypt(text, id, true); }
  /** Decrypts a segment after authorization. @author czhcc */
  public String decrypt(String cipher, UUID id) { return crypt(cipher, id, false); }
  private String crypt(String value, UUID id, boolean encrypt) {
    if (key == null) throw new IllegalArgumentException("trace_key_unavailable");
    try {
      byte[] nonce = new byte[12], data;
      if (encrypt) { new SecureRandom().nextBytes(nonce); data = value.getBytes(StandardCharsets.UTF_8); }
      else { byte[] all = Base64.getDecoder().decode(value); nonce = Arrays.copyOf(all, 12); data = Arrays.copyOfRange(all, 12, all.length); }
      var c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(("agent-trace/v1/" + id).getBytes(StandardCharsets.UTF_8)); byte[] result = c.doFinal(data);
      if (!encrypt) return new String(result, StandardCharsets.UTF_8);
      byte[] all = Arrays.copyOf(nonce, nonce.length + result.length); System.arraycopy(result, 0, all, nonce.length, result.length); return Base64.getEncoder().encodeToString(all);
    } catch (Exception e) { throw new IllegalArgumentException("trace_payload_unavailable"); }
  }
}
