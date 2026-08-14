package com.medassist.agent.generation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Generation metrics use bounded metadata labels and never accept request or event bodies. */
final class GenerationMetrics {
  private static final Set<String> RESUME_OUTCOMES =
      Set.of("success", "rejected", "expired", "unavailable");

  private final MeterRegistry registry;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger bufferedEvents = new AtomicInteger();
  private final ConcurrentHashMap<String, SessionObservation> sessions = new ConcurrentHashMap<>();
  private final DistributionSummary bufferedBytes;
  private final Timer sessionDuration;
  private final Timer recoveryLatency;
  private final Timer backgroundDuration;

  GenerationMetrics(final MeterRegistry registry) {
    this.registry = registry;
    this.bufferedBytes =
        DistributionSummary.builder("medassist.generation.session.buffer.bytes")
            .description("Approved event bytes appended to generation buffers")
            .register(registry);
    this.sessionDuration =
        Timer.builder("medassist.generation.session.duration")
            .description("Generation session duration from creation to terminal state")
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofSeconds(5))
            .register(registry);
    this.recoveryLatency =
        Timer.builder("medassist.generation.session.recovery.latency")
            .description("Time from resumed subscription to first replayed event")
            .register(registry);
    this.backgroundDuration =
        Timer.builder("medassist.generation.session.background.duration")
            .description("Generation time spent without an attached subscriber")
            .register(registry);
    registry.gauge("medassist.generation.sessions.active", active);
    registry.gauge("medassist.generation.session.buffer.events", bufferedEvents);
  }

  void created(final String generationId, final Instant createdAt) {
    if (sessions.putIfAbsent(generationId, new SessionObservation(createdAt)) == null) {
      active.incrementAndGet();
    }
  }

  void terminal(
      final String generationId, final GenerationStatus status, final Instant terminalAt) {
    final SessionObservation observation = sessions.remove(generationId);
    if (observation == null) {
      return;
    }
    active.updateAndGet(value -> Math.max(0, value - 1));
    bufferedEvents.updateAndGet(value -> Math.max(0, value - observation.bufferedEvents.get()));
    sessionCounter(status.name().toLowerCase(Locale.ROOT)).increment();
    final Duration elapsed = Duration.between(observation.createdAt, terminalAt);
    sessionDuration.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
  }

  void resume(final String outcome) {
    if (!RESUME_OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("unsupported generation resume outcome");
    }
    Counter.builder("medassist.generation.session.resume")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }

  void duplicate() {
    Counter.builder("medassist.generation.session.replay")
        .tag("outcome", "duplicate")
        .register(registry)
        .increment();
  }

  void append(final String generationId, final long bytes) {
    final SessionObservation observation = sessions.get(generationId);
    if (observation != null) {
      observation.bufferedEvents.incrementAndGet();
      bufferedEvents.incrementAndGet();
    }
    bufferedBytes.record(bytes);
  }

  void recordRecoveryLatency(final Duration duration) {
    recoveryLatency.record(duration);
  }

  void recordBackgroundDuration(final Duration duration) {
    backgroundDuration.record(duration);
  }

  private Counter sessionCounter(final String outcome) {
    return Counter.builder("medassist.generation.sessions")
        .tag("outcome", outcome)
        .register(registry);
  }

  private static final class SessionObservation {
    private final Instant createdAt;
    private final AtomicInteger bufferedEvents = new AtomicInteger();

    private SessionObservation(final Instant createdAt) {
      this.createdAt = createdAt;
    }
  }
}
