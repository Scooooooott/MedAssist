package com.medassist.agent.execution;

import com.medassist.agent.application.ChatMessage;
import com.medassist.agent.state.AgentStateProjection;
import java.util.List;
import java.util.Objects;

public record AgentGenerationContext(
    AgentStateProjection state,
    RuntimeSafetyEvidence runtimeSafetyEvidence,
    List<ChatMessage> history) {
  public AgentGenerationContext(final AgentStateProjection state) {
    this(state, RuntimeSafetyEvidence.empty(), List.of());
  }

  public AgentGenerationContext(
      final AgentStateProjection state, final RuntimeSafetyEvidence runtimeSafetyEvidence) {
    this(state, runtimeSafetyEvidence, List.of());
  }

  public AgentGenerationContext {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(runtimeSafetyEvidence, "runtimeSafetyEvidence");
    history = List.copyOf(Objects.requireNonNull(history, "history"));
  }

  @Override
  public String toString() {
    return "AgentGenerationContext[state="
        + state
        + ", runtimeSafetyEvidence="
        + runtimeSafetyEvidence
        + "]";
  }
}
