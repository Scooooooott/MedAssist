package com.medassist.ingestion.pipeline.store;

/** Transaction boundary for publishing one validated document index. */
public interface IndexingPersistencePort {
  IndexingPersistenceResult persist(IndexingPersistenceRequest request);
}
