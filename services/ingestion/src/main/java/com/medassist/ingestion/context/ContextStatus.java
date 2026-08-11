package com.medassist.ingestion.context;

/** Records how a context prefix was produced. */
public enum ContextStatus {
  OFF,
  RULE_BASED,
  RULE_BASED_CACHE_HIT,
  LLM_GENERATED,
  LLM_GENERATED_CACHE_HIT,
  LLM_FALLBACK_RULE_BASED,
  LLM_FALLBACK_RULE_BASED_CACHE_HIT
}
