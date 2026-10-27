package com.trs.app.rag.ingestion;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * TXT/Markdown parser preserving decoded text and source offsets; no HTML is executed.
 *
 * @author agent
 */
@Component
public class DocumentParser {
  /**
   * Strictly decodes supported text and extracts source-preserving structure.
   *
   * @author agent
   */
  public ParsedDocument parse(byte[] original, String format) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(original))
              .toString();
    } catch (CharacterCodingException error) {
      throw new ProcessingFailure("invalid_encoding");
    }
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    return fromText(text, format);
  }

  /**
   * Builds structural metadata without rewriting the persisted source text.
   *
   * @author agent
   */
  public ParsedDocument fromText(String text, String format) {
    if (!Set.of("text", "markdown").contains(format))
      throw new ProcessingFailure("unsupported_format");
    if (text.isBlank()
        || text.codePoints().allMatch(p -> Character.isWhitespace(p) || Character.isSpaceChar(p)))
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
        for (Node child = node.getLastChild(); child != null; child = child.getPrevious())
          stack.push(child);
      }
      found.sort(Comparator.comparingInt(Heading::getStartOffset));
      int previous = 0, codepoints = 0;
      for (var heading : found) {
        codepoints += text.codePointCount(previous, heading.getStartOffset());
        previous = heading.getStartOffset();
        headings.add(new HeadingRef(codepoints, heading.getLevel(), heading.getText().toString()));
      }
    }
    return new ParsedDocument(text, headings);
  }

  /**
   * Finds the heading ancestry at a Unicode codepoint offset.
   *
   * @author agent
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
   * @author agent
   */
  public void assignHeadings(
      ParsedDocument parsed,
      List<ChunkPiece> pieces,
      java.util.function.BooleanSupplier cancelled) {
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
