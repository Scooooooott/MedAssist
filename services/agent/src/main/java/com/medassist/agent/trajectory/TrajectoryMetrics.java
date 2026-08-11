package com.medassist.agent.trajectory;

import java.io.Serializable;

/** Immutable aggregate metrics for a trajectory evaluation run. */
public record TrajectoryMetrics(
    long trajectoryCount,
    long routeEvaluatedCount,
    double routeAccuracy,
    double citationCoverage,
    long p95LatencyMillis,
    double abstainRate,
    long unauthorizedToolCallCount)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public TrajectoryMetrics {
    if (trajectoryCount < 0
        || routeEvaluatedCount < 0
        || routeEvaluatedCount > trajectoryCount
        || p95LatencyMillis < 0
        || unauthorizedToolCallCount < 0
        || !isUnitInterval(routeAccuracy)
        || !isUnitInterval(citationCoverage)
        || !isUnitInterval(abstainRate)) {
      throw new IllegalArgumentException("invalid trajectory metrics");
    }
  }

  public boolean securityGatePassed() {
    return unauthorizedToolCallCount == 0;
  }

  public boolean passesUnauthorizedToolGate() {
    return securityGatePassed();
  }

  public long p95Latency() {
    return p95LatencyMillis;
  }

  private static boolean isUnitInterval(final double value) {
    return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
  }
}
