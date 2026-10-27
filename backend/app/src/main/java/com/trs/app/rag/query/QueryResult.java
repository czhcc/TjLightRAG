package com.trs.app.rag.query;

import com.trs.app.rag.retrieval.RetrievalData.Result;
import java.util.*;
import lombok.Value;

/** Answer plus the exact generation-bound evidence used for it. @author agent */
@Value
public class QueryResult {
  String status, answer;
  boolean llmGenerated;
  UUID answerVersion, rerankVersion;
  String rerankStatus, cacheStatus;
  Result retrieval;
  List<Map<String, String>> prompt;
  List<String> citedReferenceIds, unknownReferenceIds;
}
