package com.medassist.agent.execution;

import java.util.List;
import java.util.Objects;

/** The only result shape that the tool adapter makes available to the agent layer. */
public record SafeToolResultProjection(
    List<SafeChunkProjection> chunks, List<SafeAggregationColumn> aggregationColumns) {
  public SafeToolResultProjection {
    Objects.requireNonNull(chunks, "chunks");
    Objects.requireNonNull(aggregationColumns, "aggregationColumns");
    chunks = List.copyOf(chunks);
    aggregationColumns = List.copyOf(aggregationColumns);
  }

  public static SafeToolResultProjection empty() {
    return new SafeToolResultProjection(List.of(), List.of());
  }
}
