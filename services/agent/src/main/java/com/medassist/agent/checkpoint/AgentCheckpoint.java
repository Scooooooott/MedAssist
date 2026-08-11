package com.medassist.agent.checkpoint;

import com.medassist.agent.state.AgentStateProjection;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public record AgentCheckpoint(
    String checkpointId,
    String traceId,
    String requestId,
    long sequence,
    CheckpointPhase phase,
    AgentStateProjection state,
    Instant savedAt)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public AgentCheckpoint {
    Objects.requireNonNull(checkpointId, "checkpointId");
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(savedAt, "savedAt");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must be non-negative");
    }
  }
}
