package com.trs.documentparser;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pages.PagesConfig;
import org.apache.tika.parser.pages.TextPolicy;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Unified Tika extraction preserving decoded text and Unicode source offsets.
 *
 * @author czhcc
 */
public class DocumentParser {
    public static final String VERSION = "tika-4.2.0-source-v1";
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 5 * 1024 * 1024;

    /**
     * Strictly decodes supported text and extracts source-preserving structure.
     *
     * @author czhcc
     */
    public ParsedDocument parse(byte[] original, String format) {
        return parse(original, format, () -> false);
    }

    /** Extracts document text while checking cancellation and bounded output. @author czhcc */
    public ParsedDocument parse(byte[] original, String format, BooleanSupplier cancelled) {
        check(cancelled);
        if (!DocumentFormats.supports(format)) throw new ProcessingFailure("unsupported_format");
        if (original == null || original.length == 0) throw new ProcessingFailure("empty_document");
        if (original.length > MAX_BYTES) throw new ProcessingFailure("document_too_large");
        boolean sourceText = format.equals("text") || format.equals("markdown");
        if (sourceText) fromText(decode(original), format);
        var metadata = new Metadata();
        var context = new ParseContext();
        var pages = new PagesConfig();
        pages.setText(TextPolicy.EXTRACT);
        pages.setInference(List.of());
        context.set(PagesConfig.class, pages);
        context.set(EmbeddedDocumentExtractor.class, new SkipEmbedded());
        var output = new TextOutput(cancelled, !sourceText);
        try (var input = TikaInputStream.get(original)) {
            if (sourceText) {
                metadata.set(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8");
                new TXTParser().parse(input, output, metadata, context);
            } else {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "document." + format);
                var tika = new AutoDetectParser();
                var detected = tika.getDetector().detect(input, metadata, context);
                if (!DocumentFormats.matches(format, detected.getBaseType().toString()))
                    throw new ProcessingFailure("document_format_mismatch");
                tika.parse(input, new BodyContentHandler(output), metadata, context);
            }
            check(cancelled);
            return fromText(output.text.toString(), format);
        } catch (EncryptedDocumentException encrypted) {
            throw new ProcessingFailure("document_encrypted");
        } catch (java.io.IOException | SAXException | TikaException failure) {
            check(cancelled);
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ProcessingFailure safe) throw safe;
                if (cause instanceof EncryptedDocumentException) throw new ProcessingFailure("document_encrypted");
            }
            throw new ProcessingFailure("document_parse_failed");
        }
    }

    private String decode(byte[] original) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(original))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new ProcessingFailure("invalid_encoding");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text;
    }

    /**
     * Builds structural metadata without rewriting the persisted source text.
     *
     * @author czhcc
     */
    public ParsedDocument fromText(String text, String format) {
        if (!DocumentFormats.supports(format)) throw new ProcessingFailure("unsupported_format");
        if (text.length() > MAX_TEXT_CHARS) throw new ProcessingFailure("document_text_too_large");
        if (text.isBlank() || text.codePoints().allMatch(p -> Character.isWhitespace(p) || Character.isSpaceChar(p)))
            throw new ProcessingFailure("empty_document");
        if (text.codePoints().anyMatch(p -> (p < 32 && p != 9 && p != 10 && p != 13) || p == 127))
            throw new ProcessingFailure("binary_content");
        var headings = new ArrayList<HeadingRef>();
        if (format.equals("markdown")) {
            Node document = Parser.builder().build().parse(text);
            var stack = new ArrayDeque<Node>();
            stack.push(document);
            var found = new ArrayList<Heading>();
            while (!stack.isEmpty()) {
                Node node = stack.pop();
                if (node instanceof Heading h) found.add(h);
                for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) stack.push(child);
            }
            found.sort(Comparator.comparingInt(Heading::getStartOffset));
            int previous = 0, codepoints = 0;
            for (var heading : found) {
                codepoints += text.codePointCount(previous, heading.getStartOffset());
                previous = heading.getStartOffset();
                headings.add(new HeadingRef(
                        codepoints, heading.getLevel(), heading.getText().toString()));
            }
        }
        return new ParsedDocument(text, headings);
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    /** Collects bounded SAX text without rewriting character offsets. @author czhcc */
    private static class TextOutput extends DefaultHandler {
        private final StringBuilder text = new StringBuilder();
        private final BooleanSupplier cancelled;
        private final boolean formattingWhitespace;

        private TextOutput(BooleanSupplier cancelled, boolean formattingWhitespace) {
            this.cancelled = cancelled;
            this.formattingWhitespace = formattingWhitespace;
        }

        /** Stores only text events; no markup or links are executed. @author czhcc */
        @Override
        public void characters(char[] chars, int start, int length) {
            check(cancelled);
            if (text.length() + length > MAX_TEXT_CHARS) throw new ProcessingFailure("document_text_too_large");
            text.append(chars, start, length);
        }

        /** Preserves whitespace emitted by the selected Tika content handler. @author czhcc */
        @Override
        public void ignorableWhitespace(char[] chars, int start, int length) {
            if (formattingWhitespace) characters(chars, start, length);
        }
    }

    /** Skips attached files rather than silently mixing their content into a document. @author czhcc */
    private static class SkipEmbedded implements EmbeddedDocumentExtractor {
        /** Disables recursive extraction of attachments and embedded objects. @author czhcc */
        @Override
        public boolean shouldParseEmbedded(Metadata metadata, ParseContext context) {
            return false;
        }

        /** Leaves embedded bytes untouched. @author czhcc */
        @Override
        public void parseEmbedded(
                TikaInputStream input,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context,
                boolean outputHtml) {}
    }

    /**
     * Finds the heading ancestry at a Unicode codepoint offset.
     *
     * @author czhcc
     */
    public List<String> headingPath(ParsedDocument parsed, int position) {
        var path = new TreeMap<Integer, String>();
        for (var heading : parsed.getHeadings()) {
            if (heading.getStart() > position) break;
            path.tailMap(heading.getLevel()).clear();
            path.put(heading.getLevel(), heading.getText());
        }
        return List.copyOf(path.values());
    }

    /**
     * Assigns ancestry in one source-ordered pass instead of rescanning all headings per block.
     *
     * @author czhcc
     */
    public void assignHeadings(
            ParsedDocument parsed, List<ChunkPiece> pieces, java.util.function.BooleanSupplier cancelled) {
        var path = new TreeMap<Integer, String>();
        int heading = 0, previous = -1;
        for (var piece : pieces) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            if (piece.getSourceStart() < previous)
                throw new IllegalArgumentException("Source intervals must be ordered");
            previous = piece.getSourceStart();
            while (heading < parsed.getHeadings().size()
                    && parsed.getHeadings().get(heading).getStart() <= piece.getSourceStart()) {
                if (heading % 1024 == 0 && cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                var current = parsed.getHeadings().get(heading++);
                path.tailMap(current.getLevel()).clear();
                path.put(current.getLevel(), current.getText());
            }
            piece.setHeadings(List.copyOf(path.values()));
        }
    }
}
