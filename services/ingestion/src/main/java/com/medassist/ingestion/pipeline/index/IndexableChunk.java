package com.medassist.ingestion.pipeline.index;

import com.medassist.domain.SourceRange;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Source-faithful, deidentified chunk record. */
public record IndexableChunk(
    UUID id,
    UUID documentVersionId,
    int ordinal,
    String sectionPath,
    String text,
    int tokenCount,
    SourceRange sourceRange,
    String breadcrumb,
    String chunkingStrategyId,
    PhiScanStatus phiScanStatus,
    Set<String> phiEntityTypes,
    String contextPrefix) {
  public IndexableChunk {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    sectionPath = Objects.requireNonNull(sectionPath, "sectionPath");
    text = requireText(text, "text");
    if (ordinal < 0 || tokenCount < 0) {
      throw new IllegalArgumentException("ordinal and tokenCount must be non-negative");
    }
    Objects.requireNonNull(sourceRange, "sourceRange");
    breadcrumb = Objects.requireNonNull(breadcrumb, "breadcrumb");
    chunkingStrategyId = requireText(chunkingStrategyId, "chunkingStrategyId");
    Objects.requireNonNull(phiScanStatus, "phiScanStatus");
    phiEntityTypes = Set.copyOf(Objects.requireNonNull(phiEntityTypes, "phiEntityTypes"));
    if (phiEntityTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
      throw new IllegalArgumentException("phiEntityTypes must not contain blank values");
    }
    if (phiScanStatus == PhiScanStatus.CLEAN && !phiEntityTypes.isEmpty()) {
      throw new IllegalArgumentException("clean PHI scan cannot contain entity types");
    }
    if (phiScanStatus == PhiScanStatus.SUSPECT && phiEntityTypes.isEmpty()) {
      throw new IllegalArgumentException("suspect PHI scan requires entity types");
    }
    contextPrefix = Objects.requireNonNull(contextPrefix, "contextPrefix");
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
