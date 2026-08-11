package com.medassist.agent.checkpoint;

import com.medassist.agent.state.AgentStateProjection;

@FunctionalInterface
public interface CheckpointRecoveryValidator {
  RecoveryDecision validate(AgentStateProjection projection, RecoveryContext context);
}
