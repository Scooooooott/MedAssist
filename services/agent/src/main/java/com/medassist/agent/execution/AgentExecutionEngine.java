package com.medassist.agent.execution;

import com.medassist.agent.state.AgentState;

@FunctionalInterface
public interface AgentExecutionEngine {
  AgentExecutionResult execute(AgentState state);
}
