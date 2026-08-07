package com.medassist.ingestion.chunking;

/** Counts tokens using the same or an intentionally compatible tokenizer as the embedder. */
@FunctionalInterface
public interface TokenCounter {
  int count(String text);
}
