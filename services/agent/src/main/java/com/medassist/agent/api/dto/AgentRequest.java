package com.medassist.agent.api.dto;

import com.medassist.agent.state.AgentRetrievalFilters;
import java.util.Objects;

/**
 * HTTP boundary DTO. The raw query is consumed by the entry service and never enters AgentState.
 */
public record AgentRequest(
    String query, String conversationId, AgentRetrievalFilters retrievalFilters) {
  public AgentRequest(final String query) {
    this(query, null, AgentRetrievalFilters.empty());
  }

  public AgentRequest(final String query, final String conversationId) {
    this(query, conversationId, AgentRetrievalFilters.empty());
  }

  public AgentRequest {
    Objects.requireNonNull(query, "query");
    retrievalFilters = retrievalFilters == null ? AgentRetrievalFilters.empty() : retrievalFilters;
    if (query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (conversationId != null
        && !conversationId.isBlank()
        && (conversationId.length() > 128 || !conversationId.matches("[A-Za-z0-9._:-]+"))) {
      throw new IllegalArgumentException("conversationId must be a bounded safe identifier");
    }
  }

  @Override
  public String toString() {
    return "AgentRequest[query=<redacted>, conversationId="
        + conversationId
        + ", retrievalFilters=<redacted>]";
  }
}
