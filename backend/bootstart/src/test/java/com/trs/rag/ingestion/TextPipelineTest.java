package com.trs.rag.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upstream differential and Unicode/source-position tests, created before implementation.
 *
 * @author agent
 */
class TextPipelineTest {
  final Cl100kTokenizer tokenizer = new Cl100kTokenizer();
  final FixedTokenChunker chunker = new FixedTokenChunker(tokenizer);
  final DocumentParser parser = new DocumentParser();

  @Test
  void realBpeTokensAndLegacyWindowsMatchPinnedUpstream() throws Exception {
    var json = JsonMapper.builder().build();
    try (var input = getClass().getResourceAsStream("/lightrag-baseline/bpe-corpus.json")) {
      assertNotNull(input);
      var root = json.readTree(input);
      assertEquals(
          "aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a", root.path("upstream_commit").asText());
      for (var test : root.path("cases")) {
        String text = test.path("input").path("content").asText();
        assertEquals(
            test.path("expected").path("tokens"),
            json.valueToTree(tokenizer.encode(text)),
            test.path("id").asText());
        int size = test.path("input").path("size").asInt(),
            overlap = test.path("input").path("overlap").asInt();
        if (test.path("expected").has("error")) {
          assertEquals(
              "ChunkTokenLimitExceededError", test.path("expected").path("error").asText());
          assertEquals(
              "chunk_token_limit",
              assertThrows(
                      ProcessingFailure.class,
                      () ->
                          chunker.legacy(
                              text,
                              size,
                              overlap,
                              test.path("input").path("delimiter").asText(),
                              true))
                  .getCode());
          continue;
        }
        var actual =
            test.path("input").has("delimiter")
                ? chunker.legacy(
                    text,
                    size,
                    overlap,
                    test.path("input").path("delimiter").asText(),
                    test.path("input").path("only").asBoolean())
                : chunker.legacy(text, size, overlap);
        assertEquals(
            test.path("expected").path("chunks"),
            json.valueToTree(actual),
            test.path("id").asText());
      }
    }
  }

  @Test
  void unicodeSafeWindowsKeepCoverageAndExactSourcePositions() {
    String text = "  # 标题🙂\r\n" + "知识图谱🚀 supports retrieval。 ".repeat(80);
    var result = chunker.split(text, 32, 8, 10000, () -> false);
    int[] points = text.codePoints().toArray();
    boolean[] covered = new boolean[points.length];
    assertTrue(result.size() > 5);
    for (var piece : result) {
      String original =
          new String(points, piece.getSourceStart(), piece.getSourceEnd() - piece.getSourceStart());
      assertEquals(original, piece.getContent());
      assertFalse(piece.getContent().contains("\uFFFD"));
      assertTrue(piece.getTokens() <= 32);
      assertEquals(piece.getTokenEnd() - piece.getTokenStart(), piece.getTokens());
      for (int i = piece.getSourceStart(); i < piece.getSourceEnd(); i++) covered[i] = true;
    }
    for (int i = 0; i < points.length; i++)
      if (!Character.isWhitespace(points[i])) assertTrue(covered[i], "Lost source codepoint " + i);
    assertThrows(
        IllegalArgumentException.class, () -> chunker.split(text, 32, 32, 1000, () -> false));
    assertThrows(ProcessingFailure.class, () -> chunker.split(text, 32, 8, 1, () -> false));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> chunker.split(text, 32, 8, 1000, () -> true));
  }

  @Test
  void markdownKeepsTextAndHeadingOffsetsWithoutExecutingHtml() {
    String text =
        "# 一级\n\n"
            + "正文🙂\n\n"
            + "## 二级\n"
            + "- item\n\n"
            + "```md\n"
            + "# not a heading\n"
            + "```\n"
            + "<script>not executed</script>\n";
    var parsed = parser.parse(text.getBytes(StandardCharsets.UTF_8), "markdown");
    assertEquals(text, parsed.getText());
    assertEquals(2, parsed.getHeadings().size());
    assertEquals(
        List.of("一级", "二级"),
        parser.headingPath(parsed, text.codePointCount(0, text.indexOf("- item"))));
    assertThrows(
        ProcessingFailure.class, () -> parser.parse(new byte[] {(byte) 0xC3, 0x28}, "text"));
    assertThrows(
        ProcessingFailure.class,
        () -> parser.parse("\0binary".getBytes(StandardCharsets.UTF_8), "text"));
    assertThrows(
        ProcessingFailure.class,
        () -> parser.parse(" \n".getBytes(StandardCharsets.UTF_8), "text"));
  }

  @Test
  void assignsOrderedHeadingPathsAndHonorsCancellation() {
    String text = "# Root\n\n正文🙂\n\n## Child\n\n细节\n".repeat(200);
    var parsed = parser.parse(text.getBytes(StandardCharsets.UTF_8), "markdown");
    var pieces = chunker.split(text, 32, 8, 10000, () -> false);
    parser.assignHeadings(parsed, pieces, () -> false);
    for (var piece : pieces)
      assertEquals(parser.headingPath(parsed, piece.getSourceStart()), piece.getHeadings());
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> parser.assignHeadings(parsed, pieces, () -> true));
  }
}
