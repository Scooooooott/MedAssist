package com.medassist.agent.checkpoint;

import com.medassist.agent.state.AgentState;
import java.util.List;
import java.util.Optional;

public interface CheckpointStore {
  void save(AgentCheckpoint checkpoint);

  Optional<AgentCheckpoint> latest(String traceId);

  List<AgentCheckpoint> history(String traceId);

  AgentState restore(
      String traceId, RecoveryContext context, CheckpointRecoveryValidator validator);
}
