package com.medassist.agent.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.agent.state.QueryClassification;
import com.medassist.agent.state.TerminationReason;
import com.medassist.domain.Role;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrajectoryMetricsCalculatorTest {
  @Test
  void aggregatesRequiredMetricsAndFailsOnUnauthorizedCalls() {
    final List<TrajectoryRecord> records =
        List.of(
            record(
                "1",
                QueryClassification.CLINICAL,
                QueryClassification.CLINICAL,
                4,
                2,
                100,
                TerminationReason.COMPLETED,
                List.of()),
            record(
                "2",
                QueryClassification.CLINICAL,
                QueryClassification.POLICY,
                2,
                1,
                200,
                TerminationReason.ABSTAINED,
                List.of()),
            record(
                "3",
                null,
                QueryClassification.MIXED,
                0,
                0,
                300,
                TerminationReason.COMPLETED,
                List.of("not_allowed")));

    final TrajectoryMetrics metrics = TrajectoryMetricsCalculator.calculate(records);

    assertEquals(3, metrics.trajectoryCount());
    assertEquals(2, metrics.routeEvaluatedCount());
    assertEquals(0.5, metrics.routeAccuracy());
    assertEquals(0.5, metrics.citationCoverage());
    assertEquals(300, metrics.p95LatencyMillis());
    assertEquals(1.0 / 3.0, metrics.abstainRate());
    assertEquals(1, metrics.unauthorizedToolCallCount());
    assertTrue(!metrics.securityGatePassed());
    assertTrue(!TrajectoryMetricsCalculator.passesUnauthorizedToolGate(records));
  }

  @Test
  void emptyAggregationIsSafeAndPassesSecurityGate() {
    final TrajectoryMetrics metrics = TrajectoryMetricsCalculator.calculate(List.of());

    assertEquals(0, metrics.trajectoryCount());
    assertEquals(0.0, metrics.routeAccuracy());
    assertEquals(0.0, metrics.citationCoverage());
    assertEquals(0, metrics.p95LatencyMillis());
    assertEquals(0.0, metrics.abstainRate());
    assertEquals(0, metrics.unauthorizedToolCallCount());
    assertTrue(metrics.securityGatePassed());
    assertTrue(TrajectoryMetricsCalculator.passesUnauthorizedToolGate(List.of()));
  }

  @Test
  void metricsMapAndJsonLineContainOnlySafeFields() {
    final TrajectoryRecord record =
        record(
            "map",
            QueryClassification.POLICY,
            QueryClassification.POLICY,
            1,
            1,
            12,
            TerminationReason.COMPLETED,
            List.of());
    final TrajectoryMetrics metrics = TrajectoryMetricsCalculator.calculate(List.of(record));

    assertEquals(true, TrajectoryExporter.toMap(metrics).get("security_gate_passed"));
    assertTrue(TrajectoryExporter.toJsonLine(metrics).contains("p95_latency_ms"));
    assertTrue(TrajectoryExporter.toJsonLines(List.of(record)).split("\\n").length == 1);
  }

  private static TrajectoryRecord record(
      final String id,
      final QueryClassification expectedQueryClass,
      final QueryClassification queryClass,
      final int candidateCount,
      final int validCount,
      final long latencyMillis,
      final TerminationReason terminationReason,
      final List<String> unauthorizedToolAttempts) {
    return new TrajectoryRecord(
        "request-" + id,
        "trace-" + id,
        "sha256:query-" + id,
        queryClass,
        expectedQueryClass,
        Role.CLINICIAN,
        Set.of("clinical_search"),
        List.of("clinical_search"),
        new CitationCoverage(candidateCount, validCount, validCount > 0),
        latencyMillis,
        terminationReason,
        terminationReason == TerminationReason.ABSTAINED ? "insufficient_evidence" : "",
        unauthorizedToolAttempts);
  }
}
