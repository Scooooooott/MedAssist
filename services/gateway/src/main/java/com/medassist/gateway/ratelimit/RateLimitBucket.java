package com.medassist.gateway.ratelimit;

import java.time.Duration;

public record RateLimitBucket(String key, long capacity, Duration window) {
  public RateLimitBucket {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("rate-limit key must not be blank");
    }
    if (capacity < 1) {
      throw new IllegalArgumentException("rate-limit capacity must be positive");
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("rate-limit window must be positive");
    }
  }
}
