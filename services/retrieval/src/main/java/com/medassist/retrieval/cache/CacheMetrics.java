package com.medassist.retrieval.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Low-cardinality cache metrics. Cache keys and request data must never become metric tags. */
@Component
public final class CacheMetrics {
  private static volatile MeterRegistry applicationRegistry = Metrics.globalRegistry;
  private final Supplier<MeterRegistry> registrySupplier;
  private final ConcurrentHashMap<String, AtomicLong> sizes = new ConcurrentHashMap<>();

  @Autowired
  public CacheMetrics(final MeterRegistry registry) {
    this.registrySupplier = () -> registry;
    applicationRegistry = registry;
  }

  public static CacheMetrics defaultInstance() {
    return new CacheMetrics(() -> applicationRegistry);
  }

  public void hit(final String cache) {
    increment("medassist.cache.hit", cache);
  }

  public void miss(final String cache) {
    increment("medassist.cache.miss", cache);
  }

  public void write(final String cache) {
    increment("medassist.cache.write", cache);
  }

  public void error(final String cache) {
    increment("medassist.cache.error", cache);
  }

  public void singleFlightJoin(final String cache) {
    increment("medassist.cache.single_flight.join", cache);
  }

  public void invalidate(final String cache) {
    increment("medassist.cache.invalidate", cache);
  }

  public void savedLatency(final String cache, final long observedLoadNanos) {
    if (observedLoadNanos <= 0) {
      return;
    }
    DistributionSummary.builder("medassist.cache.saved.latency")
        .description("Observed cache-fill latency reused by a cache hit")
        .baseUnit("milliseconds")
        .tag("cache", normalizeCache(cache))
        .register(registrySupplier.get())
        .record(observedLoadNanos / 1_000_000.0d);
  }

  public void size(final String cache, final long observedSize) {
    final String normalized = normalizeCache(cache);
    sizes
        .computeIfAbsent(
            normalized,
            ignored -> {
              final AtomicLong value = new AtomicLong();
              Gauge.builder("medassist.cache.size", value, AtomicLong::doubleValue)
                  .description("Keys observed under the MedAssist cache prefix")
                  .tag("cache", normalized)
                  .register(registrySupplier.get());
              return value;
            })
        .set(Math.max(0, observedSize));
  }

  private CacheMetrics(final Supplier<MeterRegistry> registrySupplier) {
    this.registrySupplier = registrySupplier;
  }

  private void increment(final String name, final String cache) {
    Counter.builder(name)
        .description("MedAssist cache operation count")
        .tag("cache", normalizeCache(cache))
        .register(registrySupplier.get())
        .increment();
  }

  private static String normalizeCache(final String cache) {
    return switch (cache) {
      case "embedding", "answer" -> cache;
      default -> "other";
    };
  }
}
