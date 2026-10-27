package com.trs.app.rag.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapted from LightRAG chunker/token_size.py at aaebfe1; MIT notice in THIRD_PARTY_NOTICES.md.
 * Legacy windows preserve reference behavior; ingestion uses explicitly versioned UTF-8-safe
 * boundaries.
 *
 * @author agent
 */
@Component
@RequiredArgsConstructor
public class FixedTokenChunker {
  private final RagTokenizer tokenizer;

  /**
   * Preserves upstream explicit-delimiter and delimiter-only behavior for algorithm adapters.
   *
   * @author agent
   */
  public List<Map<String, Object>> legacy(
      String text, int size, int overlap, String delimiter, boolean only) {
    if (delimiter == null || delimiter.isEmpty()) return legacy(text, size, overlap);
    if (size <= 0 || overlap < 0 || overlap >= size)
      throw new IllegalArgumentException("Invalid token window");
    var result = new ArrayList<Map<String, Object>>();
    int source = 0;
    for (String part : text.split(java.util.regex.Pattern.quote(delimiter), -1)) {
      int count = tokenizer.encode(part).length;
      if (only && count > size) throw new ProcessingFailure("chunk_token_limit");
      List<Map<String, Object>> rows;
      if (count <= size) {
        var item = new LinkedHashMap<String, Object>();
        item.put("tokens", count);
        item.put("content", strip(part));
        int left = leading(part), right = trailing(part);
        if (left < right)
          item.put(
              "_source_span",
              Map.of("start", part.codePointCount(0, left), "end", part.codePointCount(0, right)));
        rows = List.of(item);
      } else rows = legacy(part, size, overlap);
      for (var item : rows) {
        item.put("chunk_order_index", result.size());
        Object span = item.get("_source_span");
        if (span instanceof Map<?, ?> positions)
          item.put(
              "_source_span",
              Map.of(
                  "start",
                  source + ((Number) positions.get("start")).intValue(),
                  "end",
                  source + ((Number) positions.get("end")).intValue()));
        result.add(item);
      }
      source +=
          part.codePointCount(0, part.length()) + delimiter.codePointCount(0, delimiter.length());
    }
    return result;
  }

  /**
   * Executes the pinned upstream fixed-window contract for differential testing.
   *
   * @author agent
   */
  public List<Map<String, Object>> legacy(String text, int size, int overlap) {
    if (size <= 0 || overlap < 0 || overlap >= size)
      throw new IllegalArgumentException("Invalid token window");
    int[] tokens = tokenizer.encode(text), points = text.codePoints().toArray();
    var results = new ArrayList<Map<String, Object>>();
    for (int start = 0; start < tokens.length; start += size - overlap) {
      int end = Math.min(tokens.length, start + size);
      String window = tokenizer.decode(tokens, start, end);
      var item = new LinkedHashMap<String, Object>();
      item.put("tokens", end - start);
      item.put("content", strip(window));
      item.put("chunk_order_index", results.size());
      int predicted =
          tokenizer
              .decode(tokens, 0, start)
              .codePointCount(0, tokenizer.decode(tokens, 0, start).length());
      int lo = Math.max(0, predicted - 32),
          hi =
              Math.min(
                  points.length,
                  predicted
                      + window.codePointCount(0, window.length())
                      + 32
                      + window.codePointCount(0, window.length()));
      if (lo <= hi) {
        String region = new String(points, lo, hi - lo);
        int directEnd = predicted + window.codePointCount(0, window.length());
        int offset =
            predicted >= lo
                    && directEnd <= points.length
                    && new String(points, predicted, directEnd - predicted).equals(window)
                ? region.offsetByCodePoints(0, predicted - lo)
                : region.indexOf(window);
        if (offset >= 0) {
          int source = lo + region.codePointCount(0, offset);
          int leading = leading(window), trailing = trailing(window);
          if (leading < trailing)
            item.put(
                "_source_span",
                Map.of(
                    "start",
                    source + window.codePointCount(0, leading),
                    "end",
                    source + window.codePointCount(0, trailing)));
        }
      }
      results.add(item);
    }
    return results;
  }

  /**
   * Splits with verified Unicode boundaries, bounded size and cooperative cancellation.
   *
   * @author agent
   */
  public List<ChunkPiece> split(
      String text, int size, int overlap, int maxChunks, BooleanSupplier cancelled) {
    if (size < 16 || size > 4096 || overlap < 0 || overlap > size / 2 || maxChunks < 1)
      throw new IllegalArgumentException("Invalid token window");
    check(cancelled);
    int[] tokens = tokenizer.encode(text);
    check(cancelled);
    byte[] source = text.getBytes(StandardCharsets.UTF_8);
    int[] cp = new int[source.length + 1], utf16 = new int[source.length + 1];
    Arrays.fill(cp, -1);
    Arrays.fill(utf16, -1);
    int b = 0, c = 0, u = 0;
    cp[0] = 0;
    utf16[0] = 0;
    while (u < text.length()) {
      int point = text.codePointAt(u);
      b += point < 128 ? 1 : point < 2048 ? 2 : point < 65536 ? 3 : 4;
      u += Character.charCount(point);
      c++;
      cp[b] = c;
      utf16[b] = u;
    }
    int[] offsets = new int[tokens.length + 1];
    for (int i = 0; i < tokens.length; i++) {
      if (i % 1024 == 0) check(cancelled);
      offsets[i + 1] = offsets[i] + tokenizer.tokenBytes(tokens[i]).length;
    }
    if (offsets[tokens.length] != source.length) throw new ProcessingFailure("tokenizer_mismatch");
    var results = new ArrayList<ChunkPiece>();
    int start = 0;
    while (start < tokens.length) {
      check(cancelled);
      int end = Math.min(start + size, tokens.length);
      while (end > start && cp[offsets[end]] < 0) end--;
      if (end <= start) throw new ProcessingFailure("token_window_too_small");
      String window = text.substring(utf16[offsets[start]], utf16[offsets[end]]);
      int left = leading(window), right = trailing(window);
      if (left < right) {
        if (results.size() >= maxChunks) throw new ProcessingFailure("too_many_chunks");
        results.add(
            ChunkPiece.builder()
                .content(window.substring(left, right))
                .tokens(end - start)
                .tokenStart(start)
                .tokenEnd(end)
                .sourceStart(cp[offsets[start]] + window.codePointCount(0, left))
                .sourceEnd(cp[offsets[start]] + window.codePointCount(0, right))
                .headings(List.of())
                .build());
      }
      if (end == tokens.length) break;
      int next = Math.max(start + 1, end - overlap);
      while (next > start && cp[offsets[next]] < 0) next--;
      if (next <= start) next = end;
      start = next;
    }
    return results;
  }

  private void check(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new CancellationException();
  }

  private static boolean space(int point) {
    return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85;
  }

  private static int leading(String s) {
    int i = 0;
    while (i < s.length() && space(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i));
    return i;
  }

  private static int trailing(String s) {
    int i = s.length();
    while (i > 0 && space(s.codePointBefore(i))) i -= Character.charCount(s.codePointBefore(i));
    return i;
  }

  private static String strip(String s) {
    int a = leading(s), b = trailing(s);
    return a >= b ? "" : s.substring(a, b);
  }
}
