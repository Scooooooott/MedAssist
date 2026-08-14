package com.medassist.agent.llm;

import java.time.Duration;

/** Safe failure boundary for provider calls; messages must not include prompt or response data. */
public final class LlmGatewayException extends RuntimeException {
  private final LlmFailureReason reason;
  private final Duration retryAfter;

  private LlmGatewayException(
      final LlmFailureReason reason,
      final String message,
      final Throwable cause,
      final Duration retryAfter) {
    super(message, cause);
    this.reason = reason;
    this.retryAfter = retryAfter;
  }

  public static LlmGatewayException unavailable() {
    return new LlmGatewayException(
        LlmFailureReason.UNAVAILABLE, "LLM gateway is unavailable", null, null);
  }

  public static LlmGatewayException timeout(final Throwable cause) {
    return new LlmGatewayException(LlmFailureReason.TIMEOUT, "LLM gateway timed out", cause, null);
  }

  public static LlmGatewayException providerError(final Throwable ignoredCause) {
    return new LlmGatewayException(
        LlmFailureReason.PROVIDER_ERROR, "LLM provider call failed", null, null);
  }

  public static LlmGatewayException rateLimited(final Duration retryAfter) {
    return new LlmGatewayException(
        LlmFailureReason.RATE_LIMITED,
        "LLM provider rate limit reached",
        null,
        retryAfter == null ? Duration.ofSeconds(1) : retryAfter);
  }

  public static LlmGatewayException budgetExceeded() {
    return new LlmGatewayException(
        LlmFailureReason.BUDGET_EXCEEDED, "LLM budget limit reached", null, null);
  }

  public static LlmGatewayException allProvidersUnavailable() {
    return new LlmGatewayException(
        LlmFailureReason.ALL_PROVIDERS_UNAVAILABLE,
        "All configured LLM providers are unavailable",
        null,
        null);
  }

  public static LlmGatewayException invalidResponse() {
    return new LlmGatewayException(
        LlmFailureReason.INVALID_RESPONSE, "LLM provider returned an invalid response", null, null);
  }

  public static LlmGatewayException egressBlocked() {
    return new LlmGatewayException(
        LlmFailureReason.EGRESS_BLOCKED, "LLM gateway request blocked", null, null);
  }

  public LlmFailureReason reason() {
    return reason;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
