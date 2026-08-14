package com.medassist.gateway.ratelimit;

import java.time.Duration;

public record RateLimitDecision(Status status, Duration retryAfter) {
  public enum Status {
    ALLOWED,
    REJECTED,
    UNAVAILABLE
  }

  public RateLimitDecision {
    if (status == null || retryAfter == null || retryAfter.isNegative()) {
      throw new IllegalArgumentException("invalid rate-limit decision");
    }
  }

  public static RateLimitDecision allowed() {
    return new RateLimitDecision(Status.ALLOWED, Duration.ZERO);
  }

  public static RateLimitDecision rejected(final Duration retryAfter) {
    return new RateLimitDecision(Status.REJECTED, retryAfter);
  }

  public static RateLimitDecision unavailable() {
    return new RateLimitDecision(Status.UNAVAILABLE, Duration.ZERO);
  }
}
