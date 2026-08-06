package com.medassist.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record DocumentVersion(
    UUID id,
    UUID documentId,
    String version,
    String contentHash,
    LocalDate effectiveDate,
    Instant retrievedAt,
    DocumentStatus status,
    UUID supersededBy,
    String storageUri) {
  public DocumentVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(effectiveDate, "effectiveDate");
    Objects.requireNonNull(retrievedAt, "retrievedAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(storageUri, "storageUri");
  }

  public boolean isCurrentlyEffective() {
    return status == DocumentStatus.ACTIVE && !effectiveDate.isAfter(LocalDate.now());
  }

  public boolean isStale(final Duration threshold) {
    Objects.requireNonNull(threshold, "threshold");
    return retrievedAt.isBefore(Instant.now().minus(threshold));
  }
}
