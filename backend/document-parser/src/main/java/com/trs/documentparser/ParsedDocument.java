package com.trs.documentparser;

import lombok.*;

/**
 * Persisted ingestion contract value: ParsedDocument.
 *
 * @author czhcc
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
