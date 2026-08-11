package com.medassist.agent.checkpoint;

import com.medassist.agent.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCheckpointStore implements CheckpointStore {
  private final Map<String, List<AgentCheckpoint>> checkpoints = new ConcurrentHashMap<>();

  @Override
  public void save(final AgentCheckpoint checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    checkpoints.compute(
        checkpoint.traceId(),
        (ignored, existing) -> {
          final List<AgentCheckpoint> updated =
              existing == null ? new ArrayList<>() : new ArrayList<>(existing);
          updated.add(checkpoint);
          return List.copyOf(updated);
        });
  }

  @Override
  public Optional<AgentCheckpoint> latest(final String traceId) {
    return Optional.ofNullable(checkpoints.get(traceId))
        .flatMap(values -> values.stream().reduce((a, b) -> b));
  }

  @Override
  public List<AgentCheckpoint> history(final String traceId) {
    return List.copyOf(checkpoints.getOrDefault(traceId, List.of()));
  }

  @Override
  public AgentState restore(
      final String traceId,
      final RecoveryContext context,
      final CheckpointRecoveryValidator validator) {
    final AgentCheckpoint checkpoint =
        latest(traceId).orElseThrow(() -> new CheckpointRecoveryException("checkpoint not found"));
    final RecoveryDecision decision =
        Objects.requireNonNull(validator, "validator")
            .validate(checkpoint.state(), Objects.requireNonNull(context, "context"));
    if (!decision.allowed()) {
      throw new CheckpointRecoveryException(decision.reason());
    }
    return AgentState.restore(checkpoint.state());
  }
}
