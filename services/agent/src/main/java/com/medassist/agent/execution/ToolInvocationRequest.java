package com.medassist.agent.execution;

import com.medassist.agent.state.AgentRetrievalFilters;
import com.medassist.agent.state.QueryClassification;
import com.medassist.domain.Role;
import java.util.Map;
import java.util.Objects;

/** Parameter allowlist for a tool call. There is no raw document or include-superseded flag. */
public record ToolInvocationRequest(
    String toolName,
    String query,
    String queryHash,
    Role role,
    QueryClassification classification,
    int topK,
    AgentRetrievalFilters filters,
    String traceId,
    String requestId) {
  public static final int MAX_TOP_K = 50;

  public ToolInvocationRequest(
      final String toolName,
      final String query,
      final String queryHash,
      final Role role,
      final QueryClassification classification,
      final int topK,
      final Map<String, String> filters) {
    this(
        toolName,
        query,
        queryHash,
        role,
        classification,
        topK,
        AgentRetrievalFilters.fromLegacy(filters),
        "",
        "");
  }

  public ToolInvocationRequest(
      final String toolName,
      final String query,
      final String queryHash,
      final Role role,
      final QueryClassification classification,
      final int topK,
      final Map<String, String> filters,
      final String traceId,
      final String requestId) {
    this(
        toolName,
        query,
        queryHash,
        role,
        classification,
        topK,
        AgentRetrievalFilters.fromLegacy(filters),
        traceId,
        requestId);
  }

  public ToolInvocationRequest {
    requireText(toolName, "toolName");
    requireText(query, "query");
    requireText(queryHash, "queryHash");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(classification, "classification");
    Objects.requireNonNull(filters, "filters");
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(requestId, "requestId");
    if (topK < 1 || topK > MAX_TOP_K) {
      throw new IllegalArgumentException("topK must be between 1 and " + MAX_TOP_K);
    }
  }

  private static void requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
