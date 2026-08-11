package com.medassist.ingestion.pipeline.store;

import com.medassist.ingestion.versioning.VersionChainStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Verified version metadata. This type deliberately has no parser-IR field. */
public record DocumentVersionMetadata(
    UUID documentVersionId,
    String version,
    String contentHash,
    LocalDate effectiveDate,
    Instant retrievedAt,
    VersionChainStatus status,
    UUID supersededBy,
    String storageUri,
    String contentDomain,
    Map<String, String> metadata) {
  public DocumentVersionMetadata {
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    version = requireText(version, "version");
    contentHash = requireText(contentHash, "contentHash");
    Objects.requireNonNull(retrievedAt, "retrievedAt");
    Objects.requireNonNull(status, "status");
    storageUri = requireText(storageUri, "storageUri");
    contentDomain = requireText(contentDomain, "contentDomain");
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    if (status != VersionChainStatus.SUPERSEDED && supersededBy != null) {
      throw new IllegalArgumentException("only superseded versions can reference a successor");
    }
    if (status == VersionChainStatus.SUPERSEDED && supersededBy == null) {
      throw new IllegalArgumentException("superseded versions require a successor");
    }
    if (supersededBy != null && supersededBy.equals(documentVersionId)) {
      throw new IllegalArgumentException("a version cannot supersede itself");
    }
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
