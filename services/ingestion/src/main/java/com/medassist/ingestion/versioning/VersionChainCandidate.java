package com.medassist.ingestion.versioning;

import java.util.Objects;
import java.util.UUID;

/** A version candidate before lifecycle status is recalculated. */
public record VersionChainCandidate(
    UUID documentId,
    UUID documentVersionId,
    VersionMetadataResult metadata,
    VersionChainStatus currentStatus) {

  public VersionChainCandidate {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(currentStatus, "currentStatus");
  }
}
