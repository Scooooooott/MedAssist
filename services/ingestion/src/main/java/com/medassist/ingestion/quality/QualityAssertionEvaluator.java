package com.medassist.ingestion.quality;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Evaluates configured aggregate assertions and produces a fail-closed batch decision. */
public final class QualityAssertionEvaluator {
  public QualityReport evaluate(
      final String batchId,
      final QualitySnapshot snapshot,
      final Collection<QualityAssertion> assertions,
      final QualityThresholds thresholds) {
    return evaluate(batchId, snapshot, assertions, thresholds, Optional.empty());
  }

  public QualityReport evaluate(
      final String batchId,
      final QualitySnapshot snapshot,
      final Collection<QualityAssertion> assertions,
      final QualityThresholds thresholds,
      final Optional<QualityReport> previousReport) {
    requireBatchId(batchId);
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(assertions, "assertions");
    Objects.requireNonNull(thresholds, "thresholds");
    Objects.requireNonNull(previousReport, "previousReport");
    final List<QualityAssertion> rules = List.copyOf(assertions);
    ensureUniqueCodes(rules);
    final List<AssertionResult> results =
        rules.stream().map(rule -> evaluateRule(rule, snapshot, thresholds)).toList();
    final List<String> blockingFailures =
        results.stream()
            .filter(result -> result.severity() == AssertionSeverity.BLOCKING && !result.passed())
            .map(AssertionResult::assertionCode)
            .toList();
    final Optional<BatchRejection> rejection =
        blockingFailures.isEmpty()
            ? Optional.empty()
            : Optional.of(
                new BatchRejection(
                    batchId,
                    "Batch rejected: blocking quality assertions failed",
                    blockingFailures));
    final QualityReport reportWithoutTrend =
        new QualityReport(batchId, snapshot, results, rejection, Optional.empty());
    final Optional<QualityTrendComparison> trend =
        previousReport.map(previous -> compare(previous, reportWithoutTrend));
    return new QualityReport(batchId, snapshot, results, rejection, trend);
  }

  private static AssertionResult evaluateRule(
      final QualityAssertion rule,
      final QualitySnapshot snapshot,
      final QualityThresholds thresholds) {
    final double actual = rule.metric().read(snapshot);
    final Optional<QualityThreshold> threshold = thresholds.thresholdFor(rule.code());
    final boolean passed = threshold.isPresent() && threshold.get().accepts(actual);
    final String message =
        threshold.isEmpty()
            ? "Missing configured threshold for assertion"
            : passed
                ? "Quality assertion passed"
                : "Quality assertion failed: configured threshold was not met";
    return new AssertionResult(
        rule.code(),
        rule.severity(),
        passed,
        actual,
        threshold.map(value -> OptionalDouble.of(value.value())).orElseGet(OptionalDouble::empty),
        snapshot.entityTypeCounts().keySet(),
        snapshot.contentHashes(),
        message);
  }

  private static QualityTrendComparison compare(
      final QualityReport previous, final QualityReport current) {
    final Map<String, AssertionResult> previousByCode = indexResults(previous.results());
    final Map<String, Double> deltas = new HashMap<>();
    final Set<String> newlyFailed = new HashSet<>();
    final Set<String> recovered = new HashSet<>();
    for (final AssertionResult currentResult : current.results()) {
      final AssertionResult previousResult = previousByCode.get(currentResult.assertionCode());
      if (previousResult != null) {
        deltas.put(
            currentResult.assertionCode(),
            currentResult.actualValue() - previousResult.actualValue());
        if (previousResult.passed() && !currentResult.passed()) {
          newlyFailed.add(currentResult.assertionCode());
        } else if (!previousResult.passed() && currentResult.passed()) {
          recovered.add(currentResult.assertionCode());
        }
      }
    }
    return new QualityTrendComparison(previous.batchId(), deltas, newlyFailed, recovered);
  }

  private static Map<String, AssertionResult> indexResults(final List<AssertionResult> results) {
    final Map<String, AssertionResult> indexed = new HashMap<>();
    results.forEach(result -> indexed.put(result.assertionCode(), result));
    return indexed;
  }

  private static void ensureUniqueCodes(final List<QualityAssertion> assertions) {
    final Set<String> codes = new HashSet<>();
    assertions.forEach(
        assertion -> {
          if (!codes.add(assertion.code())) {
            throw new IllegalArgumentException(
                "duplicate quality assertion code: " + assertion.code());
          }
        });
  }

  private static void requireBatchId(final String batchId) {
    if (batchId == null || batchId.isBlank()) {
      throw new IllegalArgumentException("batchId must not be blank");
    }
  }
}
