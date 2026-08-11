package com.medassist.agent.trajectory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTrajectoryRecorder implements TrajectoryRecorder {
  private final Map<String, List<TrajectoryEvent>> events = new ConcurrentHashMap<>();

  @Override
  public void record(final TrajectoryEvent event) {
    Objects.requireNonNull(event, "event");
    events.compute(
        event.traceId(),
        (ignored, existing) -> {
          final List<TrajectoryEvent> updated =
              existing == null ? new ArrayList<>() : new ArrayList<>(existing);
          updated.add(event);
          return List.copyOf(updated);
        });
  }

  @Override
  public List<TrajectoryEvent> events(final String traceId) {
    return List.copyOf(events.getOrDefault(traceId, List.of()));
  }
}
