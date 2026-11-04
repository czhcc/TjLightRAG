package com.trs.app.rag.ingestion;

import com.trs.documentparser.DocumentParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the standalone document parser to the ingestion workflow.
 *
 * @author czhcc
 */
@Configuration(proxyBeanMethods = false)
public class DocumentParsingConfiguration {
  /**
   * Creates the source-preserving TXT and Markdown parser.
   *
   * @author czhcc
   */
  @Bean
  public DocumentParser documentParser() {
    return new DocumentParser();
  }
}
