package com.medassist.common.resilience;

/** Typed identities for downstream calls with distinct failure semantics. */
public enum ResilienceComponent {
  DEIDENTIFICATION(true),
  POLICY_DECISION(true),
  EMBEDDING(true),
  VECTOR_RETRIEVAL(true),
  LEXICAL_RETRIEVAL(false),
  RERANK(false),
  PARSER(false),
  CLINICAL_DATA(false),
  REDIS_CACHE(false),
  REDPANDA(false),
  LLM_PROVIDER(false),
  LLM_ALL_PROVIDERS(true);

  private final boolean failClosed;

  ResilienceComponent(final boolean failClosed) {
    this.failClosed = failClosed;
  }

  public boolean failClosed() {
    return failClosed;
  }
}
