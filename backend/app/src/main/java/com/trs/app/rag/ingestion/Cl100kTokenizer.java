package com.trs.app.rag.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Genuine cl100k_base BPE, loaded from a vocabulary with the official tiktoken SHA-256.
 *
 * @author agent
 */
@Component
public class Cl100kTokenizer implements RagTokenizer {
  private final Encoding encoding;
  private final Map<Integer, byte[]> bytes = new ConcurrentHashMap<>();

  /**
   * Initializes Cl100kTokenizer for the document pipeline.
   *
   * @author agent
   */
  public Cl100kTokenizer() {
    try (var input =
        Encoding.class.getResourceAsStream("/com/knuddels/jtokkit/cl100k_base.tiktoken")) {
      if (input == null
          || !HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))
              .equals("223921b76ee99bde995b7ff738513eef100fb51d18c93597a113bcffe865b2a7"))
        throw new IllegalStateException("Unverified tokenizer vocabulary");
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("Cannot verify tokenizer vocabulary", error);
    }
    encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
  }

  /**
   * Returns the persisted tokenizer identity.
   *
   * @author agent
   */
  public String getName() {
    return "cl100k_base";
  }

  /**
   * Encodes ordinary text without interpreting literal special tokens.
   *
   * @author agent
   */
  public int[] encode(String text) {
    return encoding.encodeOrdinary(text).toArray();
  }

  /**
   * Returns the immutable byte representation of one token.
   *
   * @author agent
   */
  public byte[] tokenBytes(int token) {
    return bytes.computeIfAbsent(
        token,
        id -> {
          var list = new IntArrayList(1);
          list.add(id);
          return encoding.decodeBytes(list);
        });
  }
}
