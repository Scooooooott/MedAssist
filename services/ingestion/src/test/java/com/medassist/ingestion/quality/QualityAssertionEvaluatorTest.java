package com.medassist.ingestion.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QualityAssertionEvaluatorTest {
  private static final String HASH = "a".repeat(64);
  private final QualityAssertionEvaluator evaluator = new QualityAssertionEvaluator();

  @Test
  void blockingFailureRejectsBatchWithExplicitSafeReason() {
    final QualitySnapshot snapshot = snapshot(10, 9, 1, 1);
    final List<QualityAssertion> assertions =
        List.of(
            new QualityAssertion(
                "residual-phi",
                "No residual PHI",
                AssertionSeverity.BLOCKING,
                QualityMetric.RESIDUAL_PHI_COUNT));
    final QualityReport report =
        evaluator.evaluate(
            "batch-1",
            snapshot,
            assertions,
            new QualityThresholds(
                Map.of("residual-phi", new QualityThreshold(ThresholdComparison.AT_MOST, 0))));

    assertFalse(report.accepted());
    assertTrue(report.rejection().isPresent());
    assertEquals(
        List.of("residual-phi"), report.rejection().orElseThrow().blockingAssertionCodes());
    assertEquals(
        "Batch rejected: blocking quality assertions failed",
        report.rejection().orElseThrow().reason());
    assertEquals(Set.of("NAME"), report.results().getFirst().entityTypes());
    assertEquals(Set.of(HASH), report.results().getFirst().contentHashes());
  }

  @Test
  void warningFailureContinuesAndRetainsSafeResult() {
    final QualityAssertion warning =
        new QualityAssertion(
            "rejection-rate",
            "Rejection rate advisory",
            AssertionSeverity.WARNING,
            QualityMetric.REJECTION_RATE);

    final QualityReport report =
        evaluator.evaluate(
            "batch-2",
            snapshot(10, 7, 3, 0),
            List.of(warning),
            new QualityThresholds(
                Map.of("rejection-rate", new QualityThreshold(ThresholdComparison.AT_MOST, 0.2))));

    assertTrue(report.accepted());
    assertEquals(1, report.warnings().size());
    assertFalse(report.warnings().getFirst().passed());
    assertEquals(0.3, report.warnings().getFirst().actualValue());
    assertEquals(
        Optional.of(0.2),
        report.warnings().getFirst().thresholdValue().stream().boxed().findFirst());
  }

  @Test
  void thresholdsAcceptInclusiveAtLeastAndAtMostBoundaries() {
    final QualityAssertion minimumRecords =
        new QualityAssertion(
            "minimum-records",
            "Minimum records",
            AssertionSeverity.BLOCKING,
            QualityMetric.TOTAL_RECORD_COUNT);
    final QualityAssertion maximumResidualPhi =
        new QualityAssertion(
            "maximum-residual-phi",
            "Maximum residual PHI",
            AssertionSeverity.BLOCKING,
            QualityMetric.RESIDUAL_PHI_COUNT);

    final QualityReport report =
        evaluator.evaluate(
            "batch-threshold-boundary",
            snapshot(10, 10, 0, 0),
            List.of(minimumRecords, maximumResidualPhi),
            new QualityThresholds(
                Map.of(
                    "minimum-records", new QualityThreshold(ThresholdComparison.AT_LEAST, 10),
                    "maximum-residual-phi", new QualityThreshold(ThresholdComparison.AT_MOST, 0))));

    assertTrue(report.accepted());
    assertTrue(report.results().stream().allMatch(AssertionResult::passed));
  }

  @Test
  void missingConfiguredThresholdFailsClosedForBlockingAssertion() {
    final QualityReport report =
        evaluator.evaluate(
            "batch-3",
            snapshot(1, 1, 0, 0),
            List.of(
                new QualityAssertion(
                    "minimum-records",
                    "At least one record",
                    AssertionSeverity.BLOCKING,
                    QualityMetric.TOTAL_RECORD_COUNT)),
            new QualityThresholds(Map.of()));

    assertFalse(report.accepted());
    assertFalse(report.results().getFirst().passed());
    assertEquals(
        "Missing configured threshold for assertion", report.results().getFirst().message());
  }

  @Test
  void trendComparisonContainsOnlyAggregateDeltasAndStatusChanges() {
    final QualityAssertion assertion =
        new QualityAssertion(
            "acceptance-rate",
            "Acceptance rate",
            AssertionSeverity.BLOCKING,
            QualityMetric.ACCEPTANCE_RATE);
    final QualityThresholds thresholds =
        new QualityThresholds(
            Map.of("acceptance-rate", new QualityThreshold(ThresholdComparison.AT_LEAST, 0.8)));
    final QualityReport previous =
        evaluator.evaluate("batch-previous", snapshot(10, 9, 1, 0), List.of(assertion), thresholds);
    final QualityReport current =
        evaluator.evaluate(
            "batch-current",
            snapshot(10, 7, 3, 0),
            List.of(assertion),
            thresholds,
            Optional.of(previous));

    assertEquals("batch-previous", current.trend().orElseThrow().previousBatchId());
    assertEquals(
        -0.2, current.trend().orElseThrow().metricDeltas().get("acceptance-rate"), 1.0e-12);
    assertEquals(Set.of("acceptance-rate"), current.trend().orElseThrow().newlyFailedAssertions());
    assertTrue(current.trend().orElseThrow().recoveredAssertions().isEmpty());
  }

  @Test
  void trendComparisonReportsRecoveredAssertions() {
    final QualityAssertion assertion =
        new QualityAssertion(
            "acceptance-rate",
            "Acceptance rate",
            AssertionSeverity.BLOCKING,
            QualityMetric.ACCEPTANCE_RATE);
    final QualityThresholds thresholds =
        new QualityThresholds(
            Map.of("acceptance-rate", new QualityThreshold(ThresholdComparison.AT_LEAST, 0.8)));
    final QualityReport previous =
        evaluator.evaluate("batch-previous", snapshot(10, 7, 3, 0), List.of(assertion), thresholds);
    final QualityReport current =
        evaluator.evaluate(
            "batch-current",
            snapshot(10, 9, 1, 0),
            List.of(assertion),
            thresholds,
            Optional.of(previous));

    assertEquals(0.2, current.trend().orElseThrow().metricDeltas().get("acceptance-rate"), 1.0e-12);
    assertTrue(current.trend().orElseThrow().newlyFailedAssertions().isEmpty());
    assertEquals(Set.of("acceptance-rate"), current.trend().orElseThrow().recoveredAssertions());
  }

  @Test
  void duplicateAssertionCodesAreRejectedBeforeEvaluation() {
    final QualityAssertion assertion =
        new QualityAssertion(
            "duplicate",
            "Duplicate assertion",
            AssertionSeverity.WARNING,
            QualityMetric.TOTAL_RECORD_COUNT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(
                "batch-duplicate",
                snapshot(1, 1, 0, 0),
                List.of(assertion, assertion),
                new QualityThresholds(Map.of())));
  }

  @Test
  void reportRetainsOnlyAggregateEvidenceAndNoPhiOriginal() {
    final String phiOriginal = "Patient Alice Example SSN 123-45-6789";
    final QualityReport report =
        evaluator.evaluate(
            "batch-safe",
            snapshot(2, 2, 0, 0),
            List.of(
                new QualityAssertion(
                    "minimum-records",
                    phiOriginal,
                    AssertionSeverity.BLOCKING,
                    QualityMetric.TOTAL_RECORD_COUNT)),
            new QualityThresholds(
                Map.of("minimum-records", new QualityThreshold(ThresholdComparison.AT_LEAST, 1))));

    assertFalse(report.toString().contains(phiOriginal));
    assertEquals(Set.of("NAME"), report.snapshot().entityTypeCounts().keySet());
    assertEquals(Set.of(HASH), report.snapshot().contentHashes());
    assertTrue(report.results().getFirst().message().contains("passed"));
  }

  @Test
  void safeSnapshotCopiesEvidenceAndRejectsNonHashContent() {
    final Map<String, Long> entityTypes = new java.util.HashMap<>(Map.of("NAME", 2L));
    final Set<String> hashes = new java.util.HashSet<>(Set.of(HASH));
    final QualitySnapshot snapshot = new QualitySnapshot(2, 2, 0, 0, entityTypes, hashes);

    entityTypes.put("DATE", 1L);
    hashes.add("b".repeat(64));

    assertEquals(Map.of("NAME", 2L), snapshot.entityTypeCounts());
    assertEquals(Set.of(HASH), snapshot.contentHashes());
  }

  private static QualitySnapshot snapshot(
      final long total, final long accepted, final long rejected, final long residualPhi) {
    return new QualitySnapshot(
        total, accepted, rejected, residualPhi, Map.of("NAME", 1L), Set.of(HASH));
  }
}
