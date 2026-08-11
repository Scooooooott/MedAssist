package com.medassist.agent.execution;

import com.medassist.agent.state.AgentState;

@FunctionalInterface
public interface AgentToolExecutor {
  ToolExecutionResult execute(AgentState state);
}
