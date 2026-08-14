package com.medassist.common.resilience;

import java.time.Duration;
import java.util.Objects;

/** Typed, configuration-ready policy for one downstream component. */
public record ComponentPolicy(
    ResilienceComponent component,
    CircuitBreakerPolicy circuitBreaker,
    RetryPolicy retry,
    TimeoutPolicy timeout,
    BulkheadPolicy bulkhead,
    RateLimitPolicy rateLimit,
    FallbackMode fallbackMode) {

  public ComponentPolicy {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(circuitBreaker, "circuitBreaker");
    Objects.requireNonNull(retry, "retry");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(bulkhead, "bulkhead");
    Objects.requireNonNull(rateLimit, "rateLimit");
    Objects.requireNonNull(fallbackMode, "fallbackMode");
    if (!fallbackAllowed(component, fallbackMode)) {
      throw new IllegalArgumentException(
          component + " does not allow fallback mode " + fallbackMode);
    }
  }

  private static boolean fallbackAllowed(
      final ResilienceComponent component, final FallbackMode fallbackMode) {
    if (fallbackMode == FallbackMode.NONE) {
      return true;
    }
    if (component.failClosed()) {
      return false;
    }
    return switch (component) {
      case LEXICAL_RETRIEVAL -> fallbackMode == FallbackMode.VECTOR_RESULTS;
      case RERANK -> fallbackMode == FallbackMode.ORIGINAL_ORDER;
      case PARSER -> fallbackMode == FallbackMode.DOCUMENT_QUARANTINE;
      case CLINICAL_DATA -> fallbackMode == FallbackMode.TOOL_ERROR;
      case REDIS_CACHE -> fallbackMode == FallbackMode.CACHE_BYPASS;
      case REDPANDA -> fallbackMode == FallbackMode.LOCAL_BUFFER;
      case LLM_PROVIDER -> fallbackMode == FallbackMode.ALTERNATE_PROVIDER;
      default -> false;
    };
  }

  public record CircuitBreakerPolicy(
      boolean enabled,
      float failureRateThreshold,
      int slidingWindowSize,
      int minimumNumberOfCalls,
      int permittedCallsInHalfOpenState,
      Duration openStateDuration) {
    public CircuitBreakerPolicy {
      if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
        throw new IllegalArgumentException("failureRateThreshold must be in (0, 100]");
      }
      if (slidingWindowSize < 1
          || minimumNumberOfCalls < 1
          || minimumNumberOfCalls > slidingWindowSize
          || permittedCallsInHalfOpenState < 1) {
        throw new IllegalArgumentException("invalid circuit breaker call counts");
      }
      requirePositive(openStateDuration, "openStateDuration");
    }
  }

  public record RetryPolicy(boolean enabled, int maxAttempts, Duration waitDuration) {
    public RetryPolicy {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be positive");
      }
      requireNonNegative(waitDuration, "waitDuration");
    }
  }

  public record TimeoutPolicy(Duration duration) {
    public TimeoutPolicy {
      requirePositive(duration, "timeout duration");
    }
  }

  public record BulkheadPolicy(boolean enabled, int maxConcurrentCalls, Duration maxWaitDuration) {
    public BulkheadPolicy {
      if (maxConcurrentCalls < 1) {
        throw new IllegalArgumentException("maxConcurrentCalls must be positive");
      }
      requireNonNegative(maxWaitDuration, "maxWaitDuration");
    }
  }

  public record RateLimitPolicy(
      boolean enabled, int limitForPeriod, Duration refreshPeriod, Duration timeoutDuration) {
    public RateLimitPolicy {
      if (limitForPeriod < 1) {
        throw new IllegalArgumentException("limitForPeriod must be positive");
      }
      requirePositive(refreshPeriod, "refreshPeriod");
      requireNonNegative(timeoutDuration, "rate limiter timeoutDuration");
    }
  }

  private static void requirePositive(final Duration value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNegative(final Duration value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
