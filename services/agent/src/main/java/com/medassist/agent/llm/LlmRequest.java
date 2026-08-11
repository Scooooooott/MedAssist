package com.medassist.agent.llm;

import java.util.Objects;

/** Immutable prompt envelope. Prompt contents are intentionally omitted from toString(). */
public record LlmRequest(String systemPrompt, String userPrompt, LlmCallMetadata metadata) {
  public LlmRequest {
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("system prompt is required");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("user prompt is required");
    }
    metadata = Objects.requireNonNull(metadata, "metadata");
  }

  public LlmRequest(final String systemPrompt, final String userPrompt) {
    this(systemPrompt, userPrompt, LlmCallMetadata.unconfigured());
  }

  @Override
  public String toString() {
    return "LlmRequest[metadata=" + metadata + "]";
  }
}
