package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BoundedDegradationTrajectorySinkTest {
  @Test
  void keepsOnlySafeMetadataAndEvictsTheOldestEntryAtCapacity() {
    final BoundedDegradationTrajectorySink sink = new BoundedDegradationTrajectorySink(1);
    sink.publish(event("FIRST"));
    sink.publish(event("SECOND"));

    assertThat(sink.snapshot())
        .extracting(DegradationTrajectoryEvent::code)
        .containsExactly("SECOND");
    assertThat(sink.snapshot().getFirst())
        .isEqualTo(DegradationTrajectoryEvent.from(event("SECOND")));
  }

  private static DegradationEvent event(final String code) {
    return new DegradationEvent(
        ResilienceComponent.RERANK,
        code,
        "RERANK",
        FallbackMode.ORIGINAL_ORDER,
        "free-form reason must not be projected",
        Instant.parse("2026-08-11T12:00:00Z"));
  }
}
