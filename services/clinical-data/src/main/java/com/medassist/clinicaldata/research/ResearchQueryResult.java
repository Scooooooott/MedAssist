package com.medassist.clinicaldata.research;

import java.util.List;

public record ResearchQueryResult(
    ResearchView view,
    List<ResearchAggregateRow> rows,
    int kAnonymity,
    boolean kAnonymityExempt,
    int suppressedGroupCount,
    boolean truncated) {
  public ResearchQueryResult {
    rows = List.copyOf(rows);
    if (kAnonymity < 2 || suppressedGroupCount < 0) {
      throw new IllegalArgumentException("invalid research query result metadata");
    }
  }
}
