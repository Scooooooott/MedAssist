package com.medassist.agent.llm;

import java.util.Objects;

/** Immutable provider-neutral model response. Response contents are omitted from toString(). */
public record LlmResponse(String content, LlmCallMetadata metadata, LlmUsage usage, LlmCost cost) {
  public LlmResponse {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("response content is required");
    }
    metadata = Objects.requireNonNull(metadata, "metadata");
    usage = Objects.requireNonNull(usage, "usage");
    cost = Objects.requireNonNull(cost, "cost");
  }

  @Override
  public String toString() {
    return "LlmResponse[metadata=" + metadata + ", usage=" + usage + ", cost=" + cost + "]";
  }
}
