package com.medassist.clinicaldata.query;

import java.util.List;

public record StructuredQueryResult(
    StructuredView view,
    List<StructuredResultColumn> columns,
    List<StructuredAggregateRow> rows,
    boolean truncated,
    boolean kAnonymityExempt) {
  public StructuredQueryResult {
    if (view == null) {
      throw new IllegalArgumentException("view is required");
    }
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }
}
