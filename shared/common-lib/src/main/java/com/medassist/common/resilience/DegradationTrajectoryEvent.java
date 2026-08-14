package com.medassist.common.resilience;

import java.time.Instant;

/** Safe trajectory projection; it deliberately excludes the free-form reason. */
public record DegradationTrajectoryEvent(
    ResilienceComponent component,
    String code,
    String affectedStage,
    FallbackMode fallbackMode,
    Instant occurredAt) {
  public static DegradationTrajectoryEvent from(final DegradationEvent event) {
    return new DegradationTrajectoryEvent(
        event.component(),
        event.code(),
        event.affectedStage(),
        event.fallbackMode(),
        event.occurredAt());
  }
}
