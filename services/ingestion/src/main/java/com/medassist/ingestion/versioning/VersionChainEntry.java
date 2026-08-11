package com.medassist.ingestion.versioning;

import java.util.Objects;
import java.util.UUID;

/** Planned lifecycle state ready for a later persistence adapter. */
public record VersionChainEntry(
    UUID documentId,
    UUID documentVersionId,
    VersionMetadataResult metadata,
    VersionChainStatus status,
    UUID supersededBy) {

  public VersionChainEntry {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(status, "status");
    if (status != VersionChainStatus.SUPERSEDED && supersededBy != null) {
      throw new IllegalArgumentException("only superseded versions can reference a successor");
    }
  }
}
