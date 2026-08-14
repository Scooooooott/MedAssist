package com.medassist.common.resilience;

import java.time.Instant;
import java.util.Objects;

/** Safe event projection suitable for both metrics dimensions and audit payloads. */
public record DegradationEvent(
    ResilienceComponent component,
    String code,
    String affectedStage,
    FallbackMode fallbackMode,
    String reason,
    Instant occurredAt) {

  public DegradationEvent {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(affectedStage, "affectedStage");
    Objects.requireNonNull(fallbackMode, "fallbackMode");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public static DegradationEvent from(
      final ResilienceComponent component, final Degradation degradation) {
    Objects.requireNonNull(degradation, "degradation");
    return new DegradationEvent(
        component,
        degradation.code(),
        degradation.affectedStage(),
        degradation.fallbackMode(),
        degradation.reason(),
        Instant.now());
  }
}
