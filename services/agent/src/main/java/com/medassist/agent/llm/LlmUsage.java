package com.medassist.agent.llm;

import java.util.Objects;

/** Token usage reported by a provider; -1 means that the provider did not report it. */
public record LlmUsage(
    long inputTokens, long outputTokens, long totalTokens, LlmCallMetadata metadata) {
  public LlmUsage {
    if (inputTokens < -1 || outputTokens < -1 || totalTokens < -1) {
      throw new IllegalArgumentException("token counts must be -1 or non-negative");
    }
    metadata = Objects.requireNonNull(metadata, "metadata");
  }

  public LlmUsage(final long inputTokens, final long outputTokens, final long totalTokens) {
    this(inputTokens, outputTokens, totalTokens, LlmCallMetadata.unconfigured());
  }

  public static LlmUsage unknown() {
    return unknown(LlmCallMetadata.unconfigured());
  }

  public static LlmUsage unknown(final LlmCallMetadata metadata) {
    return new LlmUsage(-1, -1, -1, metadata);
  }
}
