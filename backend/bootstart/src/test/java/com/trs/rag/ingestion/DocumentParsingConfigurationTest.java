package com.trs.rag.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.trs.app.rag.ingestion.DocumentParsingConfiguration;
import com.trs.documentparser.DocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Verifies application wiring of the framework-independent parser module.
 *
 * @author czhcc
 */
class DocumentParsingConfigurationTest {
  @Test
  void registersOneStandaloneParserBean() {
    try (var context = new AnnotationConfigApplicationContext(DocumentParsingConfiguration.class)) {
      assertEquals(1, context.getBeansOfType(DocumentParser.class).size());
      assertEquals("text🙂", context.getBean(DocumentParser.class).fromText("text🙂", "text").getText());
    }
  }
}
