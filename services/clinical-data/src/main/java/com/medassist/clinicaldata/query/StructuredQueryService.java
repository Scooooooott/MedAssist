package com.medassist.clinicaldata.query;

import java.util.Objects;

/** Executes only after a reusable security boundary has accepted the request. */
public final class StructuredQueryService {
  private final StructuredQueryBoundary boundary;
  private final StructuredQueryExecutor executor;

  public StructuredQueryService(
      final StructuredQueryBoundary boundary, final StructuredQueryExecutor executor) {
    this.boundary = Objects.requireNonNull(boundary, "boundary");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public StructuredQueryResult execute(final StructuredQueryRequest request) {
    boundary.validate(request);
    return executor.execute(request);
  }
}
