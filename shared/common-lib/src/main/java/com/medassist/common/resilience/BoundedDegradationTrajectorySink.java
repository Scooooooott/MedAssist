package com.medassist.common.resilience;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded in-process trajectory projection for safe degradation metadata. */
public final class BoundedDegradationTrajectorySink implements DegradationTrajectorySink {
  private final int capacity;
  private final Deque<DegradationTrajectoryEvent> events = new ArrayDeque<>();

  public BoundedDegradationTrajectorySink(final int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("trajectory capacity must be positive");
    }
    this.capacity = capacity;
  }

  @Override
  public synchronized void publish(final DegradationEvent event) {
    Objects.requireNonNull(event, "event");
    if (events.size() == capacity) {
      events.removeFirst();
    }
    events.addLast(DegradationTrajectoryEvent.from(event));
  }

  public synchronized List<DegradationTrajectoryEvent> snapshot() {
    return List.copyOf(new ArrayList<>(events));
  }
}
