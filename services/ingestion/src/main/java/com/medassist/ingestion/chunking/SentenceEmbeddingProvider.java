package com.medassist.ingestion.chunking;

/** Narrow port for semantic chunking; production embedding clients stay outside this module. */
@FunctionalInterface
public interface SentenceEmbeddingProvider {
  double[] embed(String sentence);
}
