package com.medassist.common.resilience;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Emits a safe event that metric and audit adapters can consume independently. */
@FunctionalInterface
public interface DegradationRecorder {
  void record(DegradationEvent event);

  default void record(final ResilienceComponent component, final Degradation degradation) {
    record(DegradationEvent.from(component, degradation));
  }

  static DegradationRecorder noop() {
    return ignored -> {};
  }

  static DegradationRecorder composite(final DegradationRecorder... recorders) {
    final List<DegradationRecorder> delegates =
        Arrays.stream(recorders)
            .map(recorder -> Objects.requireNonNull(recorder, "recorder"))
            .toList();
    return event -> delegates.forEach(recorder -> recorder.record(event));
  }
}
