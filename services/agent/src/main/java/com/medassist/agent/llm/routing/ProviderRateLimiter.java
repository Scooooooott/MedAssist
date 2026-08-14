package com.medassist.agent.llm.routing;

import java.time.Instant;

public interface ProviderRateLimiter {
  boolean tryAcquire(String providerId, int requestsPerMinute, Instant now);
}
