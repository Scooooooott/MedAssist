package com.medassist.agent.llm;

/** Safe failure boundary for provider calls; messages must not include prompt or response data. */
public final class LlmGatewayException extends RuntimeException {
  private final LlmFailureReason reason;

  private LlmGatewayException(
      final LlmFailureReason reason, final String message, final Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public static LlmGatewayException unavailable() {
    return new LlmGatewayException(
        LlmFailureReason.UNAVAILABLE, "LLM gateway is unavailable", null);
  }

  public static LlmGatewayException timeout(final Throwable cause) {
    return new LlmGatewayException(LlmFailureReason.TIMEOUT, "LLM gateway timed out", cause);
  }

  public static LlmGatewayException providerError(final Throwable ignoredCause) {
    return new LlmGatewayException(
        LlmFailureReason.PROVIDER_ERROR, "LLM provider call failed", null);
  }

  public static LlmGatewayException invalidResponse() {
    return new LlmGatewayException(
        LlmFailureReason.INVALID_RESPONSE, "LLM provider returned an invalid response", null);
  }

  public static LlmGatewayException egressBlocked() {
    return new LlmGatewayException(
        LlmFailureReason.EGRESS_BLOCKED, "LLM gateway request blocked", null);
  }

  public LlmFailureReason reason() {
    return reason;
  }
}
