package com.medassist.agent.trajectory;

import com.medassist.agent.state.QueryClassification;
import com.medassist.agent.state.TerminationReason;
import com.medassist.domain.Role;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable per-trajectory evaluation result. */
public record EvalRecord(
    String requestId,
    String traceId,
    String queryHash,
    QueryClassification queryClass,
    QueryClassification expectedQueryClass,
    Role role,
    Set<String> expectedTools,
    List<String> actualToolCalls,
    CitationCoverage citationCoverage,
    long latencyMillis,
    TerminationReason terminationReason,
    String abstainReason,
    List<String> unauthorizedToolAttempts,
    Boolean routeCorrect)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public EvalRecord {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(queryHash, "queryHash");
    Objects.requireNonNull(queryClass, "queryClass");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(expectedTools, "expectedTools");
    Objects.requireNonNull(actualToolCalls, "actualToolCalls");
    Objects.requireNonNull(citationCoverage, "citationCoverage");
    Objects.requireNonNull(abstainReason, "abstainReason");
    Objects.requireNonNull(unauthorizedToolAttempts, "unauthorizedToolAttempts");
    if (requestId.isBlank()
        || traceId.isBlank()
        || !queryHash.startsWith("sha256:")
        || queryHash.length() <= "sha256:".length()) {
      throw new IllegalArgumentException("evaluation identifiers must be safe metadata");
    }
    if (latencyMillis < 0) {
      throw new IllegalArgumentException("latencyMillis must be non-negative");
    }
    expectedTools = copyToolNames(expectedTools, "expectedTools");
    actualToolCalls = copyToolNames(actualToolCalls, "actualToolCalls");
    unauthorizedToolAttempts = copyToolNames(unauthorizedToolAttempts, "unauthorizedToolAttempts");
  }

  public static EvalRecord from(final TrajectoryRecord trajectory) {
    Objects.requireNonNull(trajectory, "trajectory");
    return new EvalRecord(
        trajectory.requestId(),
        trajectory.traceId(),
        trajectory.queryHash(),
        trajectory.queryClass(),
        trajectory.expectedQueryClass(),
        trajectory.role(),
        trajectory.expectedTools(),
        trajectory.actualToolCalls(),
        trajectory.citationCoverage(),
        trajectory.latencyMillis(),
        trajectory.terminationReason(),
        trajectory.abstainReason(),
        trajectory.unauthorizedToolAttempts(),
        trajectory.routeEvaluated() ? trajectory.routeCorrect() : null);
  }

  public boolean routeEvaluated() {
    return routeCorrect != null;
  }

  public boolean abstained() {
    return terminationReason != null && terminationReason != TerminationReason.COMPLETED;
  }

  public int unauthorizedToolCallCount() {
    return unauthorizedToolAttempts.size();
  }

  private static Set<String> copyToolNames(final Set<String> names, final String field) {
    names.forEach(name -> validateToolName(name, field));
    return Set.copyOf(names);
  }

  private static List<String> copyToolNames(final List<String> names, final String field) {
    names.forEach(name -> validateToolName(name, field));
    return List.copyOf(names);
  }

  private static void validateToolName(final String name, final String field) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(field + " must contain non-blank tool names");
    }
  }
}
