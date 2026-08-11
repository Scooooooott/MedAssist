package com.medassist.agent.execution;

import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.TerminationReason;
import java.util.Objects;

public record AgentExecutionResult(AgentState state, String answer) {
  public AgentExecutionResult {
    Objects.requireNonNull(state, "state");
    if (state.terminationReason() == null) {
      throw new IllegalArgumentException("execution result must be terminated");
    }
  }

  public boolean abstained() {
    return state.terminationReason() != TerminationReason.COMPLETED;
  }
}
