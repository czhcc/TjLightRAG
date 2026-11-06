package com.trs.documentparser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the standalone parser contract before the module migration.
 *
 * @author czhcc
 */
class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser();

    @Test
    void decodesUtf8BomAndPreservesOriginalLineEndings() {
        String text = "第一行🙂\r\n第二行\n";
        var parsed = parser.parse(("\uFEFF" + text).getBytes(StandardCharsets.UTF_8), "text");
        assertEquals(text, parsed.getText());
        assertTrue(parsed.getHeadings().isEmpty());
        assertEquals(text, parser.fromText(text, "text").getText());
    }

    @Test
    void keepsMarkdownTextAndCodepointHeadingOffsets() {
        String text = "# Root\r\n正文🙂\r\n## Child\r\n内容\r\n```md\n# ignored\n```\n<script>x</script>\n# Next\n";
        var parsed = parser.parse(text.getBytes(StandardCharsets.UTF_8), "markdown");
        assertEquals(text, parsed.getText());
        assertEquals(3, parsed.getHeadings().size());
        int child = text.codePointCount(0, text.indexOf("## Child"));
        assertEquals(child, parsed.getHeadings().get(1).getStart());
        assertEquals(List.of("Root"), parser.headingPath(parsed, child - 1));
        assertEquals(List.of("Root", "Child"), parser.headingPath(parsed, child));
        assertEquals(List.of("Next"), parser.headingPath(parsed, text.codePointCount(0, text.length())));
    }

    @Test
    void rejectsMalformedUtf8WithSafeFailureCode() {
        var failure = assertThrows(ProcessingFailure.class, () -> parser.parse(new byte[] {(byte) 0xC3, 0x28}, "text"));
        assertEquals("invalid_encoding", failure.getCode());
        assertEquals("invalid_encoding", failure.getMessage());
    }

    @Test
    void rejectsUnsupportedBlankAndBinaryDocuments() {
        assertEquals(
                "unsupported_format",
                assertThrows(ProcessingFailure.class, () -> parser.fromText("text", "exe"))
                        .getCode());
        assertEquals(
                "empty_document",
                assertThrows(ProcessingFailure.class, () -> parser.fromText(" \r\n\u00A0", "text"))
                        .getCode());
        assertEquals(
                "binary_content",
                assertThrows(ProcessingFailure.class, () -> parser.fromText("secret\0binary", "text"))
                        .getCode());
    }

    @Test
    void assignsHeadingAncestryToOrderedChunks() {
        var parsed = parser.fromText("# Root\n\nbody🙂\n\n## Child\n\ndetails\n# Next\n", "markdown");
        var pieces = List.of(
                ChunkPiece.builder().sourceStart(0).build(),
                ChunkPiece.builder()
                        .sourceStart(parsed.getHeadings().get(1).getStart())
                        .build(),
                ChunkPiece.builder()
                        .sourceStart(parsed.getHeadings().get(2).getStart())
                        .build());
        parser.assignHeadings(parsed, pieces, () -> false);
        assertEquals(List.of("Root"), pieces.get(0).getHeadings());
        assertEquals(List.of("Root", "Child"), pieces.get(1).getHeadings());
        assertEquals(List.of("Next"), pieces.get(2).getHeadings());
    }

    @Test
    void rejectsOutOfOrderChunksAndHonorsCancellation() {
        var parsed = parser.fromText("# Root\nbody", "markdown");
        var later = ChunkPiece.builder().sourceStart(8).build();
        var earlier = ChunkPiece.builder().sourceStart(0).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.assignHeadings(parsed, List.of(later, earlier), () -> false));
        assertThrows(CancellationException.class, () -> parser.assignHeadings(parsed, List.of(earlier), () -> true));
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    CancellationException.class, () -> parser.assignHeadings(parsed, List.of(earlier), () -> false));
        } finally {
            Thread.interrupted();
        }
    }
}
