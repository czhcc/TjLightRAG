package com.trs.app.rag.ingestion;

import lombok.*;

/**
 * Persisted ingestion contract value: ParsedDocument.
 *
 * @author agent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocument {
  private String text;
  private java.util.List<HeadingRef> headings;
}
