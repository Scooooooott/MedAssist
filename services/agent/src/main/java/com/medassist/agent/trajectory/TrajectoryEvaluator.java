package com.medassist.agent.trajectory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Converts safe trajectory projections into per-record evaluation results. */
public final class TrajectoryEvaluator {
  private TrajectoryEvaluator() {}

  public static EvalRecord evaluate(final TrajectoryRecord trajectory) {
    return EvalRecord.from(trajectory);
  }

  public static List<EvalRecord> evaluateAll(
      final Collection<? extends TrajectoryRecord> trajectories) {
    Objects.requireNonNull(trajectories, "trajectories");
    return trajectories.stream().map(TrajectoryEvaluator::evaluate).toList();
  }

  public static boolean passesUnauthorizedToolGate(final TrajectoryRecord trajectory) {
    return evaluate(trajectory).unauthorizedToolCallCount() == 0;
  }

  public static boolean passesUnauthorizedToolGate(
      final Collection<? extends TrajectoryRecord> trajectories) {
    Objects.requireNonNull(trajectories, "trajectories");
    return trajectories.stream()
        .map(TrajectoryEvaluator::evaluate)
        .allMatch(evaluation -> evaluation.unauthorizedToolCallCount() == 0);
  }
}
