package com.trs.documentparser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

/** Verifies real multi-format extraction through the unified parser. @author czhcc */
class TikaDocumentParserTest {
  private final DocumentParser parser = new DocumentParser();

  @Test
  void extractsPdfDocxAndSpreadsheetText() throws Exception {
    var pdf = new ByteArrayOutputStream();
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(30, 700);
        content.showText("PDF knowledge evidence");
        content.endText();
      }
      document.save(pdf);
    }
    assertTrue(parser.parse(pdf.toByteArray(), "pdf").getText().contains("PDF knowledge evidence"));
    var docx = new ByteArrayOutputStream();
    try (var document = new XWPFDocument()) {
      document.createParagraph().createRun().setText("Word 知识库正文🙂");
      document.write(docx);
    }
    var parsed = parser.parse(docx.toByteArray(), "docx");
    assertTrue(parsed.getText().contains("Word 知识库正文🙂"));
    assertEquals(parsed.getText(), parser.fromText(parsed.getText(), "docx").getText());
    var xlsx = new ByteArrayOutputStream();
    try (var document = new XSSFWorkbook()) {
      document.createSheet("知识").createRow(0).createCell(0).setCellValue("Spreadsheet evidence");
      document.write(xlsx);
    }
    assertTrue(parser.parse(xlsx.toByteArray(), "xlsx").getText().contains("Spreadsheet evidence"));
  }

  @Test
  void extractsHtmlAndRtfWithoutExecutingMarkup() {
    String html = "<!DOCTYPE html><html><body><h1>标题</h1><p>HTML evidence</p>"
        + "<script>secretScript()</script><img src='http://127.0.0.1:1/never-fetch'></body></html>";
    String text = parser.parse(html.getBytes(StandardCharsets.UTF_8), "html").getText();
    assertTrue(text.contains("HTML evidence"));
    assertFalse(text.contains("<script>"));
    assertFalse(text.contains("secretScript"));
    assertTrue(parser.parse("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Arial;}}\\f0 RTF evidence\\par}".getBytes(StandardCharsets.US_ASCII), "rtf")
        .getText().contains("RTF evidence"));
  }

  @Test
  void rejectsCorruptAndMismatchedFilesAndChecksCancellation() {
    assertEquals("document_parse_failed", assertThrows(ProcessingFailure.class,
        () -> parser.parse("%PDF-invalid".getBytes(StandardCharsets.UTF_8), "pdf")).getCode());
    assertEquals("document_format_mismatch", assertThrows(ProcessingFailure.class,
        () -> parser.parse("plain text pretending to be PDF".getBytes(StandardCharsets.UTF_8), "pdf")).getCode());
    assertThrows(CancellationException.class,
        () -> parser.parse("text".getBytes(StandardCharsets.UTF_8), "text", () -> true));
  }

  @Test
  void restoresParsedBinaryTextAndRetainsCodepointChunkOffsets() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var document = new XWPFDocument()) {
      document.createParagraph().createRun().setText("中文🙂 document source");
      document.write(bytes);
    }
    var parsed = parser.parse(bytes.toByteArray(), "docx");
    var restored = parser.fromText(parsed.getText(), "docx");
    assertEquals(parsed.getText(), restored.getText());
    var piece = ChunkPiece.builder().sourceStart(0)
        .sourceEnd(restored.getText().codePointCount(0, restored.getText().length())).build();
    parser.assignHeadings(restored, List.of(piece), () -> false);
    assertNotNull(piece.getHeadings());
  }

  @Test
  void extractsLegacySpreadsheetPresentationAndCsv() throws Exception {
    var xls = new ByteArrayOutputStream();
    try (var document = new HSSFWorkbook()) {
      document.createSheet("sheet").createRow(0).createCell(0).setCellValue("Legacy sheet evidence");
      document.write(xls);
    }
    assertTrue(parser.parse(xls.toByteArray(), "xls").getText().contains("Legacy sheet evidence"));
    var pptx = new ByteArrayOutputStream();
    try (var document = new XMLSlideShow()) {
      document.createSlide().createTextBox().setText("Slide evidence");
      document.write(pptx);
    }
    assertTrue(parser.parse(pptx.toByteArray(), "pptx").getText().contains("Slide evidence"));
    assertTrue(parser.parse("name,value\nentry,CSV evidence\n".getBytes(StandardCharsets.UTF_8), "csv")
        .getText().contains("CSV evidence"));
  }

  @Test
  void reportsEncryptedAndEmptyPdfAndEnforcesSizeLimits() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
      document.protect(new StandardProtectionPolicy("owner-password", "reader-password", new AccessPermission()));
      document.save(bytes);
    }
    assertEquals("document_encrypted", assertThrows(ProcessingFailure.class,
        () -> parser.parse(bytes.toByteArray(), "pdf")).getCode());
    bytes.reset();
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(bytes);
    }
    assertEquals("empty_document", assertThrows(ProcessingFailure.class,
        () -> parser.parse(bytes.toByteArray(), "pdf")).getCode());
    assertEquals("document_too_large", assertThrows(ProcessingFailure.class,
        () -> parser.parse(new byte[DocumentParser.MAX_BYTES + 1], "pdf")).getCode());
    assertEquals("document_text_too_large", assertThrows(ProcessingFailure.class,
        () -> parser.fromText("x".repeat(DocumentParser.MAX_TEXT_CHARS + 1), "text")).getCode());
  }
}
