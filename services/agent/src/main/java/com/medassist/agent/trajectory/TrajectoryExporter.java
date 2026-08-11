package com.medassist.agent.trajectory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Exports only safe trajectory metadata as maps or newline-delimited JSON. */
public final class TrajectoryExporter {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TrajectoryExporter() {}

  public static Map<String, Object> toMap(final TrajectoryRecord record) {
    Objects.requireNonNull(record, "record");
    final Map<String, Object> output = new LinkedHashMap<>();
    output.put("record_type", "trajectory");
    output.put("request_id", record.requestId());
    output.put("trace_id", record.traceId());
    output.put("query_hash", record.queryHash());
    output.put("query_class", record.queryClass().name());
    output.put(
        "expected_query_class",
        record.expectedQueryClass() == null ? null : record.expectedQueryClass().name());
    output.put("role", record.role().name());
    output.put("expected_tools", record.expectedTools());
    output.put("actual_tool_calls", record.actualToolCalls());
    putCitationFields(output, record.citationCoverage());
    output.put("latency_ms", record.latencyMillis());
    output.put(
        "termination_reason",
        record.terminationReason() == null ? null : record.terminationReason().name());
    output.put("abstain_reason", record.abstainReason());
    output.put("unauthorized_tool_attempts", record.unauthorizedToolAttempts());
    output.put("unauthorized_tool_call_count", record.unauthorizedToolCallCount());
    return Collections.unmodifiableMap(output);
  }

  public static Map<String, Object> toMap(final EvalRecord record) {
    Objects.requireNonNull(record, "record");
    final Map<String, Object> output = new LinkedHashMap<>(toMap(asTrajectory(record)));
    output.put("record_type", "eval");
    output.put("route_correct", record.routeCorrect());
    return Collections.unmodifiableMap(output);
  }

  public static Map<String, Object> toMap(final TrajectoryMetrics metrics) {
    Objects.requireNonNull(metrics, "metrics");
    final Map<String, Object> output = new LinkedHashMap<>();
    output.put("record_type", "trajectory_metrics");
    output.put("trajectory_count", metrics.trajectoryCount());
    output.put("route_evaluated_count", metrics.routeEvaluatedCount());
    output.put("route_accuracy", metrics.routeAccuracy());
    output.put("citation_coverage", metrics.citationCoverage());
    output.put("p95_latency_ms", metrics.p95LatencyMillis());
    output.put("abstain_rate", metrics.abstainRate());
    output.put("unauthorized_tool_call_count", metrics.unauthorizedToolCallCount());
    output.put("security_gate_passed", metrics.securityGatePassed());
    return Collections.unmodifiableMap(output);
  }

  public static String toJsonLine(final TrajectoryRecord record) {
    return toJsonLine(toMap(record));
  }

  public static String toJsonLine(final EvalRecord record) {
    return toJsonLine(toMap(record));
  }

  public static String toJsonLine(final TrajectoryMetrics metrics) {
    return toJsonLine(toMap(metrics));
  }

  public static String toJsonLines(final Collection<? extends TrajectoryRecord> records) {
    Objects.requireNonNull(records, "records");
    return records.stream().map(TrajectoryExporter::toJsonLine).collect(Collectors.joining("\n"));
  }

  private static String toJsonLine(final Map<String, Object> map) {
    try {
      return OBJECT_MAPPER.writeValueAsString(map);
    } catch (final JsonProcessingException exception) {
      throw new IllegalStateException("trajectory export failed", exception);
    }
  }

  private static void putCitationFields(
      final Map<String, Object> output, final CitationCoverage citationCoverage) {
    output.put("citation_coverage", citationCoverage.ratio());
    output.put("citation_candidate_count", citationCoverage.candidateCount());
    output.put("citation_valid_count", citationCoverage.validCount());
    output.put("citation_sufficient_evidence", citationCoverage.sufficientEvidence());
  }

  private static TrajectoryRecord asTrajectory(final EvalRecord record) {
    return new TrajectoryRecord(
        record.requestId(),
        record.traceId(),
        record.queryHash(),
        record.queryClass(),
        record.expectedQueryClass(),
        record.role(),
        record.expectedTools(),
        record.actualToolCalls(),
        record.citationCoverage(),
        record.latencyMillis(),
        record.terminationReason(),
        record.abstainReason(),
        record.unauthorizedToolAttempts());
  }
}
