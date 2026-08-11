package com.medassist.retrieval.application.generation;

/** Token counts reported by the model, or -1 when the provider did not report them. */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
  public static TokenUsage unknown() {
    return new TokenUsage(-1, -1, -1);
  }
}
