package com.medassist.agent.api.generation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medassist.agent.state.AgentRetrievalFilters;
import java.util.Objects;

public record GenerationCreateRequest(
    String query,
    AgentRetrievalFilters filters,
    @JsonProperty("idempotency_key") String idempotencyKey) {
  private static final int MAX_QUERY_CHARACTERS = 20_000;

  public GenerationCreateRequest {
    Objects.requireNonNull(query, "query");
    if (query.isBlank() || query.length() > MAX_QUERY_CHARACTERS) {
      throw new IllegalArgumentException("query must be non-blank and bounded");
    }
    filters = filters == null ? AgentRetrievalFilters.empty() : filters;
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{16,128}")) {
      throw new IllegalArgumentException("idempotency_key is invalid");
    }
  }

  @Override
  public String toString() {
    return "GenerationCreateRequest[query=<redacted>, filters=<redacted>,"
        + " idempotencyKey=<redacted>]";
  }
}
