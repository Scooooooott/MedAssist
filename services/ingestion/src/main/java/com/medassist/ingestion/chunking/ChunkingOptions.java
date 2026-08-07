package com.medassist.ingestion.chunking;

public record ChunkingOptions(int targetTokens, int maxTokens, int minTokens, int overlapTokens) {
  public ChunkingOptions {
    if (targetTokens <= 0 || maxTokens <= 0 || minTokens < 0 || overlapTokens < 0) {
      throw new IllegalArgumentException("chunking token settings must be positive where required");
    }
    if (targetTokens > maxTokens) {
      throw new IllegalArgumentException("targetTokens must not exceed maxTokens");
    }
    if (minTokens > targetTokens) {
      throw new IllegalArgumentException("minTokens must not exceed targetTokens");
    }
    if (overlapTokens >= maxTokens) {
      throw new IllegalArgumentException("overlapTokens must be less than maxTokens");
    }
  }

  public static ChunkingOptions defaults() {
    return new ChunkingOptions(512, 1024, 100, 50);
  }
}
