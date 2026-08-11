package com.medassist.ingestion.context;

/** Narrow provider port; implementations may map the request to a cached prompt prefix. */
public interface ContextLlmClient {
  ContextLlmResponse generate(ContextLlmRequest request);
}
