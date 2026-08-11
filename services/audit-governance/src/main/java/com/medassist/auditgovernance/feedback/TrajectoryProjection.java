package com.medassist.auditgovernance.feedback;

import java.util.List;

/**
 * Safe trajectory projection. It contains no query, candidate text, span text, or raw span data.
 */
public record TrajectoryProjection(
    String traceId,
    List<String> nodeNames,
    List<String> candidateIds,
    List<String> degradationCodes,
    String modelVersion,
    String policyVersion) {
  public TrajectoryProjection {
    if (traceId == null || traceId.isBlank()) {
      throw new IllegalArgumentException("traceId is required");
    }
    nodeNames = List.copyOf(nodeNames);
    candidateIds = List.copyOf(candidateIds);
    degradationCodes = List.copyOf(degradationCodes);
    if (modelVersion == null || modelVersion.isBlank()) {
      throw new IllegalArgumentException("modelVersion is required");
    }
    if (policyVersion == null || policyVersion.isBlank()) {
      throw new IllegalArgumentException("policyVersion is required");
    }
  }
}
