package com.medassist.agent.trajectory;

import com.medassist.agent.state.AgentState;
import com.medassist.agent.state.QueryClassification;
import com.medassist.agent.state.TerminationReason;
import com.medassist.domain.Role;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, deidentified trajectory projection used by the eval layer. */
public record TrajectoryRecord(
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
    List<String> unauthorizedToolAttempts)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public TrajectoryRecord {
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
      throw new IllegalArgumentException("trajectory identifiers must be safe metadata");
    }
    if (latencyMillis < 0) {
      throw new IllegalArgumentException("latencyMillis must be non-negative");
    }
    expectedTools = copyToolNames(expectedTools, "expectedTools");
    actualToolCalls = copyToolNames(actualToolCalls, "actualToolCalls");
    unauthorizedToolAttempts = copyToolNames(unauthorizedToolAttempts, "unauthorizedToolAttempts");
  }

  public static TrajectoryRecord from(
      final AgentState state,
      final QueryClassification expectedQueryClass,
      final Set<String> expectedTools,
      final long latencyMillis) {
    return from(
        state,
        expectedQueryClass,
        expectedTools,
        latencyMillis,
        defaultAbstainReason(stateTermination(state)));
  }

  public static TrajectoryRecord from(
      final AgentState state,
      final QueryClassification expectedQueryClass,
      final Set<String> expectedTools,
      final long latencyMillis,
      final String abstainReason) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(expectedTools, "expectedTools");
    final List<String> actualToolCalls =
        state.toolCalls().stream().map(toolCall -> toolCall.toolName()).toList();
    final List<String> unauthorizedToolAttempts =
        actualToolCalls.stream().filter(tool -> !state.allowedTools().contains(tool)).toList();
    return new TrajectoryRecord(
        state.requestId(),
        state.traceId(),
        state.queryHash(),
        state.classification(),
        expectedQueryClass,
        state.role(),
        expectedTools,
        actualToolCalls,
        CitationCoverage.from(state.citationSummary()),
        latencyMillis,
        state.terminationReason(),
        abstainReason,
        unauthorizedToolAttempts);
  }

  public static TrajectoryRecord from(
      final AgentState state,
      final QueryClassification expectedQueryClass,
      final Set<String> expectedTools,
      final Collection<TrajectoryEvent> events) {
    return from(state, expectedQueryClass, expectedTools, latencyFrom(events));
  }

  public static long latencyFrom(final Collection<TrajectoryEvent> events) {
    Objects.requireNonNull(events, "events");
    return events.stream()
        .filter(Objects::nonNull)
        .filter(event -> event.phase() == TrajectoryPhase.EXIT)
        .mapToLong(TrajectoryEvent::durationMillis)
        .sum();
  }

  public boolean routeEvaluated() {
    return expectedQueryClass != null;
  }

  public boolean routeCorrect() {
    return routeEvaluated() && expectedQueryClass == queryClass;
  }

  public boolean abstained() {
    return terminationReason != null && terminationReason != TerminationReason.COMPLETED;
  }

  public int unauthorizedToolCallCount() {
    return unauthorizedToolAttempts.size();
  }

  private static TerminationReason stateTermination(final AgentState state) {
    Objects.requireNonNull(state, "state");
    return state.terminationReason();
  }

  private static String defaultAbstainReason(final TerminationReason reason) {
    if (reason == null || reason == TerminationReason.COMPLETED) {
      return "";
    }
    return switch (reason) {
      case ABSTAINED -> "insufficient_evidence";
      case MAX_STEPS -> "max_steps";
      case TIMEOUT -> "timeout";
      case DEIDENTIFICATION_FAILED -> "deidentification_failed";
      case RECOVERY_REJECTED -> "recovery_rejected";
      case EXECUTION_ERROR -> "execution_error";
      case COMPLETED -> "";
    };
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
