package com.medassist.agent.state;

import com.medassist.agent.execution.SafeAggregationColumn;
import com.medassist.domain.Role;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable persistence projection. It deliberately has no draft or chunk text fields. */
public record AgentStateProjection(
    String stateVersion,
    String traceId,
    String requestId,
    String deidentifiedQuery,
    String queryHash,
    Role role,
    QueryClassification classification,
    Set<String> allowedTools,
    List<ChunkCandidateMetadata> candidateChunks,
    List<SafeAggregationColumn> aggregationColumns,
    List<ToolCallRecord> toolCalls,
    DraftMetadata draftMetadata,
    CitationSummary citationSummary,
    int retryCount,
    AgentNode currentNode,
    long stepCount,
    TerminationReason terminationReason)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public AgentStateProjection {
    Objects.requireNonNull(stateVersion, "stateVersion");
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(deidentifiedQuery, "deidentifiedQuery");
    Objects.requireNonNull(queryHash, "queryHash");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(classification, "classification");
    Objects.requireNonNull(allowedTools, "allowedTools");
    Objects.requireNonNull(candidateChunks, "candidateChunks");
    Objects.requireNonNull(aggregationColumns, "aggregationColumns");
    Objects.requireNonNull(toolCalls, "toolCalls");
    Objects.requireNonNull(citationSummary, "citationSummary");
    Objects.requireNonNull(currentNode, "currentNode");
    allowedTools = Set.copyOf(allowedTools);
    candidateChunks = List.copyOf(candidateChunks);
    aggregationColumns = List.copyOf(aggregationColumns);
    toolCalls = List.copyOf(toolCalls);
    if (retryCount < 0 || stepCount < 0) {
      throw new IllegalArgumentException("state counters must be non-negative");
    }
  }
}
