package com.medassist.agent.execution;

import com.medassist.agent.state.AgentState;

@FunctionalInterface
public interface AgentRouter {
  RouteDecision route(AgentState state);
}
