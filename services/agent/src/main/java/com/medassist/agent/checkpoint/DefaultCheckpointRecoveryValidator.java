package com.medassist.agent.checkpoint;

import com.medassist.agent.state.AgentStateProjection;

public final class DefaultCheckpointRecoveryValidator implements CheckpointRecoveryValidator {
  @Override
  public RecoveryDecision validate(
      final AgentStateProjection projection, final RecoveryContext context) {
    if (!projection.stateVersion().equals(context.expectedStateVersion())) {
      return RecoveryDecision.reject("state version is not supported");
    }
    if (!projection.role().equals(context.currentRole())) {
      return RecoveryDecision.reject("role authorization changed");
    }
    if (!context.allowedTools().containsAll(projection.allowedTools())) {
      return RecoveryDecision.reject("tool authorization changed");
    }
    return RecoveryDecision.allow();
  }
}
