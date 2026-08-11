package com.medassist.agent.execution;

import java.util.List;
import java.util.Objects;

/** Untrusted backend response. It must be projected before crossing into agent state. */
public record ToolBackendResult(
    String toolName,
    List<ToolBackendChunk> chunks,
    List<SafeAggregationColumn> aggregationColumns) {
  public ToolBackendResult {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    Objects.requireNonNull(chunks, "chunks");
    Objects.requireNonNull(aggregationColumns, "aggregationColumns");
    chunks = List.copyOf(chunks);
    aggregationColumns = List.copyOf(aggregationColumns);
  }

  public static ToolBackendResult empty(final String toolName) {
    return new ToolBackendResult(toolName, List.of(), List.of());
  }
}
