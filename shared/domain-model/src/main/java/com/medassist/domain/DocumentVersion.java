package com.medassist.domain;

import java.time.Clock;
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
    Objects.requireNonNull(retrievedAt, "retrievedAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(storageUri, "storageUri");
  }

  public boolean isCurrentlyEffective() {
    return isCurrentlyEffective(Clock.systemDefaultZone());
  }

  public boolean isCurrentlyEffective(final Clock clock) {
    Objects.requireNonNull(clock, "clock");
    return status == DocumentStatus.ACTIVE
        && effectiveDate != null
        && !effectiveDate.isAfter(LocalDate.now(clock));
  }

  public boolean isStale(final Duration threshold) {
    Objects.requireNonNull(threshold, "threshold");
    return isStale(threshold, Clock.systemDefaultZone());
  }

  public boolean isStale(final Duration threshold, final Clock clock) {
    Objects.requireNonNull(threshold, "threshold");
    Objects.requireNonNull(clock, "clock");
    if (effectiveDate == null) {
      return false;
    }
    return effectiveDate
        .atStartOfDay(clock.getZone())
        .toInstant()
        .isBefore(clock.instant().minus(threshold));
  }
}
