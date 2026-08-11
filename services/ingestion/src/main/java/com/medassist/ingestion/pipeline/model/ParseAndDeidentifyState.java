package com.medassist.ingestion.pipeline.model;

import com.medassist.domain.DocumentIR;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of the parse-then-deidentify boundary.
 *
 * <p>A quarantined state never contains either IR. This makes it impossible for callers to
 * accidentally publish undeidentified content after a de-identification failure.
 */
public record ParseAndDeidentifyState(
    IngestionWorkItem workItem,
    DocumentIR deidentifiedDocument,
    Map<String, Integer> phiTypeCounts,
    String policyVersion,
    List<String> warnings,
    ProcessingStatus status,
    FailureStage failureStage,
    String failureReason) {

  public ParseAndDeidentifyState {
    Objects.requireNonNull(workItem, "workItem must not be null");
    phiTypeCounts = Map.copyOf(Objects.requireNonNull(phiTypeCounts, "phiTypeCounts"));
    warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    Objects.requireNonNull(policyVersion, "policyVersion");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(failureStage, "failureStage");
    Objects.requireNonNull(failureReason, "failureReason");

    if (status == ProcessingStatus.QUARANTINED) {
      if (deidentifiedDocument != null) {
        throw new IllegalArgumentException("quarantined state must not expose document IR");
      }
      if (failureStage == FailureStage.NONE || failureReason.isBlank()) {
        throw new IllegalArgumentException("quarantined state requires a safe failure reason");
      }
      if (!phiTypeCounts.isEmpty() || !policyVersion.isBlank()) {
        throw new IllegalArgumentException(
            "quarantined state must not expose de-identification output");
      }
    } else {
      if (deidentifiedDocument == null) {
        throw new IllegalArgumentException("successful state requires a de-identified document IR");
      }
      if (failureStage != FailureStage.NONE || !failureReason.isBlank()) {
        throw new IllegalArgumentException("successful state cannot contain a failure");
      }
      if (policyVersion.isBlank()) {
        throw new IllegalArgumentException("successful state requires a policy version");
      }
    }
  }

  public boolean isQuarantined() {
    return status == ProcessingStatus.QUARANTINED;
  }

  public com.medassist.ingestion.discovery.ObjectDiscoveryResult discoveryResult() {
    return workItem.discoveryResult();
  }

  public java.util.UUID logicalDocumentId() {
    return workItem.logicalDocumentId();
  }

  public java.util.UUID documentVersionId() {
    return workItem.documentVersionId();
  }
}
