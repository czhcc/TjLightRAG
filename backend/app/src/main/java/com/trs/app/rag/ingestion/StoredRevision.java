package com.trs.app.rag.ingestion;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;

/**
 * Immutable JPA view of revision input metadata; fenced JDBC owns checkpoint updates.
 *
 * @author agent
 */
@Entity
@Table(name = "rag_document_revision")
@Immutable
@Getter
@NoArgsConstructor
public class StoredRevision {
  @EmbeddedId private ScopedRevisionId id;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(nullable = false, length = 16)
  private String format;

  @Column(nullable = false, length = 64)
  private String tokenizer;

  @Column(name = "chunk_strategy", nullable = false, length = 64)
  private String chunkStrategy;

  @Column(name = "parser_version", nullable = false, length = 64)
  private String parserVersion;

  @Column(name = "chunk_size", nullable = false)
  private int chunkSize;

  @Column(nullable = false)
  private int overlap;

  @Column(name = "parsed_text", columnDefinition = "text")
  private String parsedText;
}
