package com.trs.app.rag.ingestion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Injectable tokenizer contract; scalar fixtures and actual BPE are never conflated.
 *
 * @author agent
 */
public interface RagTokenizer {
  /**
   * Returns the versioned encoding name.
   *
   * @author agent
   */
  String getName();

  /**
   * Encodes ordinary text.
   *
   * @author agent
   */
  int[] encode(String text);

  /**
   * Returns one token byte sequence.
   *
   * @author agent
   */
  byte[] tokenBytes(int token);

  /**
   * Decodes a half-open token range, matching upstream replacement behavior.
   *
   * @author agent
   */
  default String decode(int[] tokens, int start, int end) {
    var bytes = new ByteArrayOutputStream();
    for (int i = start; i < end; i++) bytes.writeBytes(tokenBytes(tokens[i]));
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
