package com.medassist.ingestion.pipeline.index;

/** Narrow application port for a batch passage-embedding call. */
public interface BatchEmbeddingPort {
  BatchEmbeddingResponse embed(BatchEmbeddingRequest request);
}
