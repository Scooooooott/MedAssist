package com.medassist.ingestion.pipeline.store;

/** Counts returned after one document transaction commits. */
public record IndexingPersistenceResult(int chunkCount, int embeddingCount) {
  public IndexingPersistenceResult {
    if (chunkCount < 0 || embeddingCount < 0) {
      throw new IllegalArgumentException("persisted counts must be non-negative");
    }
  }
}
