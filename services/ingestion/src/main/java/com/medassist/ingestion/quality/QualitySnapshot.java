package com.medassist.ingestion.quality;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** PHI-safe aggregate input for quality evaluation. No source text is represented here. */
public record QualitySnapshot(
    long totalRecordCount,
    long acceptedRecordCount,
    long rejectedRecordCount,
    long residualPhiFindingCount,
    Map<String, Long> entityTypeCounts,
    Set<String> contentHashes) {
  private static final String SHA256 = "[0-9a-fA-F]{64}";

  public QualitySnapshot {
    requireNonNegative(totalRecordCount, "totalRecordCount");
    requireNonNegative(acceptedRecordCount, "acceptedRecordCount");
    requireNonNegative(rejectedRecordCount, "rejectedRecordCount");
    requireNonNegative(residualPhiFindingCount, "residualPhiFindingCount");
    if (acceptedRecordCount + rejectedRecordCount > totalRecordCount) {
      throw new IllegalArgumentException("accepted and rejected records exceed total records");
    }
    Objects.requireNonNull(entityTypeCounts, "entityTypeCounts");
    if (entityTypeCounts.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getValue() < 0)) {
      throw new IllegalArgumentException("entity type counts must be safe non-negative aggregates");
    }
    entityTypeCounts = Map.copyOf(entityTypeCounts);
    Objects.requireNonNull(contentHashes, "contentHashes");
    if (contentHashes.stream().anyMatch(hash -> hash == null || !hash.matches(SHA256))) {
      throw new IllegalArgumentException("contentHashes must contain only SHA-256 hashes");
    }
    contentHashes = Set.copyOf(contentHashes);
  }

  private static void requireNonNegative(final long value, final String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }
}
