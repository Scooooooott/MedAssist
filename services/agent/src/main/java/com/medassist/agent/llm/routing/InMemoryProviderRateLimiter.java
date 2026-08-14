package com.medassist.agent.llm.routing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Explicit per-provider fixed-window limiter. */
public final class InMemoryProviderRateLimiter implements ProviderRateLimiter {
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Window> windows = new HashMap<>();

  @Override
  public boolean tryAcquire(
      final String providerId, final int requestsPerMinute, final Instant now) {
    Objects.requireNonNull(providerId, "providerId");
    Objects.requireNonNull(now, "now");
    if (requestsPerMinute <= 0) {
      throw new IllegalArgumentException("requestsPerMinute must be positive");
    }
    final Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
    lock.lock();
    try {
      final Window current = windows.get(providerId);
      if (current == null || !current.minute().equals(minute)) {
        windows.put(providerId, new Window(minute, 1));
        return true;
      }
      if (current.count() >= requestsPerMinute) {
        return false;
      }
      windows.put(providerId, new Window(minute, current.count() + 1));
      return true;
    } finally {
      lock.unlock();
    }
  }

  private record Window(Instant minute, int count) {}
}
