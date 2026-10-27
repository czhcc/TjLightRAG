package com.trs.app.rag.retrieval;

import com.trs.app.rag.retrieval.RetrievalData.*;
import java.util.List;

/** Snapshot-bound data access used by the actual production search algorithm. @author agent */
public interface SearchBackend {
  /** Recalls entities by descending cosine similarity. @author agent */
  List<Node> entities(float[] vector, int k, double threshold);

  /** Recalls edges by descending cosine similarity. @author agent */
  List<Edge> relations(float[] vector, int k, double threshold);

  /** Recalls document chunks by descending cosine similarity. @author agent */
  List<Chunk> chunks(float[] vector, int k, double threshold);

  /** Loads the deduplicated incident edges of recalled nodes. @author agent */
  List<Edge> adjacent(List<Node> nodes);

  /** Loads endpoints in first-appearance order of recalled edges. @author agent */
  List<Node> endpoints(List<Edge> edges);
}
