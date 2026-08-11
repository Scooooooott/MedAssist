package com.medassist.ingestion.pipeline.index;

import com.medassist.ingestion.chunking.Chunker;

/** Resolves a configured strategy without coupling the pipeline to its construction. */
public interface ChunkingStrategyRegistry {
  Chunker resolve(String strategyId);
}
