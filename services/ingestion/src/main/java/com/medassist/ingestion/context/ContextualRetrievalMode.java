package com.medassist.ingestion.context;

/** Controls how context is prepared for a chunk. */
public enum ContextualRetrievalMode {
  OFF,
  RULE_BASED,
  LLM_GENERATED
}
