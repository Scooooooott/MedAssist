package com.medassist.agent.trajectory;

import com.medassist.agent.state.AgentNode;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public record TrajectoryEvent(
    String traceId,
    long sequence,
    AgentNode node,
    TrajectoryPhase phase,
    long step,
    Instant recordedAt,
    long durationMillis)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public TrajectoryEvent {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(recordedAt, "recordedAt");
    if (sequence < 0 || step < 0 || durationMillis < 0) {
      throw new IllegalArgumentException("trajectory counters must be non-negative");
    }
  }
}
