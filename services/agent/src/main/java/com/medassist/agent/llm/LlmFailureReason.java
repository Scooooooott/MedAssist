package com.medassist.agent.llm;

public enum LlmFailureReason {
  UNAVAILABLE,
  TIMEOUT,
  PROVIDER_ERROR,
  INVALID_RESPONSE,
  EGRESS_BLOCKED
}
