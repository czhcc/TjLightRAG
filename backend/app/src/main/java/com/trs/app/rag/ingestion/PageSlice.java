package com.trs.app.rag.ingestion;

import java.util.List;
import lombok.*;

/**
 * Bounded page response; total does not include inaccessible knowledge bases.
 *
 * @author agent
 */
@Getter
@AllArgsConstructor
public class PageSlice<T> {
  private final List<T> items;
  private final long total;
  private final int page;
  private final int size;
}
