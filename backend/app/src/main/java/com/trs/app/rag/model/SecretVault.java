package com.trs.app.rag.model;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256 GCM credential storage; the master key stays outside PostgreSQL. @author agent */
@Component
public class SecretVault {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  /** Reads an optional deployment-managed Base64 AES key. @author agent */
  public SecretVault(@Value("${rag.models.master-key:}") String value) {
    try {
      key = value.isBlank() ? null : Base64.getDecoder().decode(value);
      if (key != null && key.length != 32) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Model master key must be Base64 encoded 32 bytes");
    }
  }

  /** Reports readiness without revealing a key. @author agent */
  public boolean configured() {
    return key != null;
  }

  /** Encrypts a key with a random nonce and profile-bound associated data. @author agent */
  public String encrypt(String secret, UUID profile) {
    if (secret == null || secret.isEmpty()) return "";
    if (key == null) throw new ModelFailure("master_key_missing");
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var c = cipher(Cipher.ENCRYPT_MODE, nonce, profile);
      byte[] encrypted = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      byte[] output = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, output, 0, nonce.length);
      System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(output);
    } catch (ModelFailure e) {
      throw e;
    } catch (Exception e) {
      throw new ModelFailure("secret_unavailable");
    }
  }

  /** Decrypts only within the server-side transport boundary. @author agent */
  public String decrypt(String value, UUID profile) {
    if (value.isEmpty()) return "";
    if (key == null) throw new ModelFailure("master_key_missing");
    try {
      byte[] input = Base64.getDecoder().decode(value);
      return new String(
          cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(input, 12), profile)
              .doFinal(Arrays.copyOfRange(input, 12, input.length)),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new ModelFailure("secret_unavailable");
    }
  }

  private Cipher cipher(int mode, byte[] nonce, UUID profile) throws Exception {
    var c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    c.updateAAD(profile.toString().getBytes(StandardCharsets.UTF_8));
    return c;
  }
}
