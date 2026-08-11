package com.medassist.agent.execution;

import com.medassist.agent.state.ChunkCandidateMetadata;
import com.medassist.agent.state.ToolCallRecord;
import java.util.List;
import java.util.Objects;

public record ToolExecutionResult(
    List<ToolCallRecord> toolCalls,
    List<ChunkCandidateMetadata> candidateChunks,
    ToolExecutionStatus status,
    String failureReason,
    SafeToolResultProjection safeProjection,
    RuntimeSafetyEvidence runtimeSafetyEvidence,
    boolean degraded) {
  public ToolExecutionResult(
      final List<ToolCallRecord> toolCalls, final List<ChunkCandidateMetadata> candidateChunks) {
    this(
        toolCalls,
        candidateChunks,
        ToolExecutionStatus.SUCCESS,
        "",
        SafeToolResultProjection.empty(),
        RuntimeSafetyEvidence.empty(),
        false);
  }

  public ToolExecutionResult(
      final List<ToolCallRecord> toolCalls,
      final List<ChunkCandidateMetadata> candidateChunks,
      final ToolExecutionStatus status) {
    this(
        toolCalls,
        candidateChunks,
        status,
        "",
        SafeToolResultProjection.empty(),
        RuntimeSafetyEvidence.empty(),
        false);
  }

  public ToolExecutionResult(
      final List<ToolCallRecord> toolCalls,
      final List<ChunkCandidateMetadata> candidateChunks,
      final ToolExecutionStatus status,
      final String failureReason,
      final SafeToolResultProjection safeProjection,
      final RuntimeSafetyEvidence runtimeSafetyEvidence) {
    this(
        toolCalls,
        candidateChunks,
        status,
        failureReason,
        safeProjection,
        runtimeSafetyEvidence,
        false);
  }

  public ToolExecutionResult {
    Objects.requireNonNull(toolCalls, "toolCalls");
    Objects.requireNonNull(candidateChunks, "candidateChunks");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(failureReason, "failureReason");
    Objects.requireNonNull(safeProjection, "safeProjection");
    Objects.requireNonNull(runtimeSafetyEvidence, "runtimeSafetyEvidence");
    toolCalls = List.copyOf(toolCalls);
    candidateChunks = List.copyOf(candidateChunks);
    if (status == ToolExecutionStatus.SUCCESS && !failureReason.isBlank()) {
      throw new IllegalArgumentException("successful tool execution cannot have a failure reason");
    }
    if (status == ToolExecutionStatus.SUCCESS && degraded) {
      throw new IllegalArgumentException("successful tool execution cannot be degraded");
    }
    if (status != ToolExecutionStatus.DEGRADED && degraded) {
      throw new IllegalArgumentException("only degraded executions may be marked degraded");
    }
    if (status == ToolExecutionStatus.DEGRADED && !degraded) {
      throw new IllegalArgumentException("degraded executions must be marked degraded");
    }
  }

  public static ToolExecutionResult empty() {
    return new ToolExecutionResult(List.of(), List.of());
  }

  public static ToolExecutionResult failClosed(final String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("fail-closed reason must not be blank");
    }
    return new ToolExecutionResult(
        List.of(),
        List.of(),
        ToolExecutionStatus.FAIL_CLOSED,
        reason,
        SafeToolResultProjection.empty(),
        RuntimeSafetyEvidence.empty(),
        false);
  }

  public static ToolExecutionResult failClosed(
      final List<ToolCallRecord> toolCalls,
      final List<ChunkCandidateMetadata> candidateChunks,
      final String reason,
      final SafeToolResultProjection safeProjection,
      final RuntimeSafetyEvidence runtimeSafetyEvidence) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("fail-closed reason must not be blank");
    }
    return new ToolExecutionResult(
        toolCalls,
        candidateChunks,
        ToolExecutionStatus.FAIL_CLOSED,
        reason,
        safeProjection,
        runtimeSafetyEvidence,
        false);
  }

  public static ToolExecutionResult rejected(final String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("rejection reason must not be blank");
    }
    return new ToolExecutionResult(
        List.of(),
        List.of(),
        ToolExecutionStatus.REJECTED,
        reason,
        SafeToolResultProjection.empty(),
        RuntimeSafetyEvidence.empty(),
        false);
  }

  public static ToolExecutionResult partialFailure(
      final List<ToolCallRecord> toolCalls,
      final List<ChunkCandidateMetadata> candidateChunks,
      final String reason,
      final SafeToolResultProjection safeProjection) {
    return partialFailure(
        toolCalls, candidateChunks, reason, safeProjection, RuntimeSafetyEvidence.empty());
  }

  public static ToolExecutionResult partialFailure(
      final List<ToolCallRecord> toolCalls,
      final List<ChunkCandidateMetadata> candidateChunks,
      final String reason,
      final SafeToolResultProjection safeProjection,
      final RuntimeSafetyEvidence runtimeSafetyEvidence) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("partial failure reason must not be blank");
    }
    final boolean degraded =
        !safeProjection.chunks().isEmpty()
            || !safeProjection.aggregationColumns().isEmpty()
            || !runtimeSafetyEvidence.chunks().isEmpty();
    final ToolExecutionStatus status =
        degraded ? ToolExecutionStatus.DEGRADED : ToolExecutionStatus.FAIL_CLOSED;
    return new ToolExecutionResult(
        toolCalls,
        candidateChunks,
        status,
        reason,
        safeProjection,
        runtimeSafetyEvidence,
        degraded);
  }

  public boolean succeeded() {
    return status == ToolExecutionStatus.SUCCESS;
  }

  public boolean degraded() {
    return degraded;
  }
}
