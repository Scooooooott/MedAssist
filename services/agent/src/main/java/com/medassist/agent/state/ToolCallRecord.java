package com.medassist.agent.state;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public record ToolCallRecord(
    String callId,
    String toolName,
    String status,
    String inputHash,
    String outputHash,
    Instant startedAt,
    Instant finishedAt)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public ToolCallRecord {
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(inputHash, "inputHash");
    Objects.requireNonNull(outputHash, "outputHash");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(finishedAt, "finishedAt");
  }
}
