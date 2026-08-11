package com.medassist.agent.execution;

import java.time.Duration;
import java.util.Objects;

public record AgentExecutionLimits(int maxSteps, Duration timeout, int maxRetries) {
  public AgentExecutionLimits {
    Objects.requireNonNull(timeout, "timeout");
    if (maxSteps < 0 || maxRetries < 0 || timeout.isNegative()) {
      throw new IllegalArgumentException("execution limits must be non-negative");
    }
  }

  public static AgentExecutionLimits defaults() {
    return new AgentExecutionLimits(16, Duration.ofSeconds(2), 2);
  }
}
