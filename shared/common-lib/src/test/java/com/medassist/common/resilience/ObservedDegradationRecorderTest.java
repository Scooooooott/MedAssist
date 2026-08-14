package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservedDegradationRecorderTest {
  @Test
  void recordsTheSameSafeCodeForMetricsAndAudit() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final List<DegradationEvent> audited = new ArrayList<>();
    final ObservedDegradationRecorder recorder =
        new ObservedDegradationRecorder(registry, null, audited::add);
    final DegradationEvent event =
        new DegradationEvent(
            ResilienceComponent.RERANK,
            "RERANK_TIMEOUT",
            "RERANK",
            FallbackMode.ORIGINAL_ORDER,
            "reranker unavailable; original order retained",
            Instant.parse("2026-08-11T12:00:00Z"));

    recorder.record(event);

    assertThat(audited).containsExactly(event);
    assertThat(
            registry
                .get("medassist.degradation.events")
                .tags(
                    "component",
                    "RERANK",
                    "code",
                    "RERANK_TIMEOUT",
                    "stage",
                    "RERANK",
                    "fallback_mode",
                    "ORIGINAL_ORDER")
                .counter()
                .count())
        .isEqualTo(1.0D);
  }
}
