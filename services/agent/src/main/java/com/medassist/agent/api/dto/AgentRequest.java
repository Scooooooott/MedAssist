package com.medassist.agent.api.dto;

import java.util.Objects;

/**
 * HTTP boundary DTO. The raw query is consumed by the entry service and never enters AgentState.
 */
public record AgentRequest(String query, String role, String conversationId) {
  public AgentRequest(final String query, final String role) {
    this(query, role, null);
  }

  public AgentRequest {
    Objects.requireNonNull(query, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (conversationId != null
        && !conversationId.isBlank()
        && (conversationId.length() > 128 || !conversationId.matches("[A-Za-z0-9._:-]+"))) {
      throw new IllegalArgumentException("conversationId must be a bounded safe identifier");
    }
  }
}
