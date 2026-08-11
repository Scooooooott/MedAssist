package com.medassist.agent.trajectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Computes deterministic, empty-safe aggregate trajectory metrics. */
public final class TrajectoryMetricsCalculator {
  private TrajectoryMetricsCalculator() {}

  public static TrajectoryMetrics calculate(
      final Collection<? extends TrajectoryRecord> trajectories) {
    Objects.requireNonNull(trajectories, "trajectories");
    return calculateEvaluations(TrajectoryEvaluator.evaluateAll(trajectories));
  }

  public static TrajectoryMetrics calculateEvaluations(
      final Collection<? extends EvalRecord> evaluations) {
    Objects.requireNonNull(evaluations, "evaluations");
    final List<EvalRecord> records = new ArrayList<>(evaluations);
    records.forEach(record -> Objects.requireNonNull(record, "evaluations cannot contain null"));
    final long total = records.size();
    final long routeEvaluated = records.stream().filter(EvalRecord::routeEvaluated).count();
    final long routeCorrect =
        records.stream().filter(record -> Boolean.TRUE.equals(record.routeCorrect())).count();
    final long citationCandidates =
        records.stream().mapToLong(record -> record.citationCoverage().candidateCount()).sum();
    final long citationValid =
        records.stream().mapToLong(record -> record.citationCoverage().validCount()).sum();
    final long abstained = records.stream().filter(EvalRecord::abstained).count();
    final long unauthorizedToolCalls =
        records.stream().mapToLong(EvalRecord::unauthorizedToolCallCount).sum();
    final List<Long> latencies =
        records.stream().map(EvalRecord::latencyMillis).sorted(Comparator.naturalOrder()).toList();
    return new TrajectoryMetrics(
        total,
        routeEvaluated,
        ratio(routeCorrect, routeEvaluated),
        ratio(citationValid, citationCandidates),
        p95(latencies),
        ratio(abstained, total),
        unauthorizedToolCalls);
  }

  public static boolean passesUnauthorizedToolGate(
      final Collection<? extends TrajectoryRecord> trajectories) {
    return calculate(trajectories).securityGatePassed();
  }

  public static boolean passesUnauthorizedToolGateEvaluations(
      final Collection<? extends EvalRecord> evaluations) {
    return calculateEvaluations(evaluations).securityGatePassed();
  }

  private static double ratio(final long numerator, final long denominator) {
    return denominator == 0 ? 0.0 : (double) numerator / denominator;
  }

  private static long p95(final List<Long> sortedLatencies) {
    if (sortedLatencies.isEmpty()) {
      return 0L;
    }
    final int rank = Math.max(1, (int) Math.ceil(sortedLatencies.size() * 0.95));
    return sortedLatencies.get(rank - 1);
  }
}
