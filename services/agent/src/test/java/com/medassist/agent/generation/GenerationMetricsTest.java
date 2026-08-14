package com.medassist.agent.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GenerationMetricsTest {
  @Test
  void exposesSloContractAndSettlesEachSessionOnlyOnce() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final GenerationMetrics metrics = new GenerationMetrics(registry);
    final Instant createdAt = Instant.parse("2026-08-11T10:00:00Z");

    metrics.created("generation-1", createdAt);
    metrics.created("generation-1", createdAt);
    metrics.append("generation-1", 128);
    metrics.append("generation-1", 256);

    assertEquals(1.0, registry.get("medassist.generation.sessions.active").gauge().value());
    assertEquals(2.0, registry.get("medassist.generation.session.buffer.events").gauge().value());

    metrics.terminal("generation-1", GenerationStatus.COMPLETED, createdAt.plusSeconds(2));
    metrics.terminal("generation-1", GenerationStatus.FAILED, createdAt.plusSeconds(3));

    assertEquals(0.0, registry.get("medassist.generation.sessions.active").gauge().value());
    assertEquals(0.0, registry.get("medassist.generation.session.buffer.events").gauge().value());
    assertEquals(
        1.0,
        registry
            .get("medassist.generation.sessions")
            .tag("outcome", "completed")
            .counter()
            .count());
    assertThrows(
        io.micrometer.core.instrument.search.MeterNotFoundException.class,
        () -> registry.get("medassist.generation.sessions").tag("outcome", "failed").counter());
    final Timer duration = registry.get("medassist.generation.session.duration").timer();
    assertEquals(1L, duration.count());
    assertEquals(2.0, duration.totalTime(TimeUnit.SECONDS));
    assertTrue(duration.takeSnapshot().histogramCounts().length > 0);
  }

  @Test
  void resumeUsesOnlyBoundedContractOutcomes() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final GenerationMetrics metrics = new GenerationMetrics(registry);

    metrics.resume("success");
    metrics.resume("rejected");
    metrics.resume("expired");
    metrics.resume("unavailable");

    for (final String outcome :
        java.util.List.of("success", "rejected", "expired", "unavailable")) {
      assertEquals(
          1.0,
          registry
              .get("medassist.generation.session.resume")
              .tag("outcome", outcome)
              .counter()
              .count());
    }
    assertThrows(IllegalArgumentException.class, () -> metrics.resume("request-body"));
  }
}
