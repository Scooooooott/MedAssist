package com.medassist.common.resilience;

/** Explicit business behavior after a protected component fails. */
public enum FallbackMode {
  NONE,
  VECTOR_RESULTS,
  ORIGINAL_ORDER,
  CACHE_BYPASS,
  DOCUMENT_QUARANTINE,
  TOOL_ERROR,
  LOCAL_BUFFER,
  ALTERNATE_PROVIDER
}
