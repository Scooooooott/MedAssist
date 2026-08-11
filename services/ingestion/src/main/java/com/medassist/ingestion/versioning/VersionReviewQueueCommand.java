package com.medassist.ingestion.versioning;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Stable command boundary for sending unresolved version metadata to manual review. */
public record VersionReviewQueueCommand(
    UUID documentId, UUID documentVersionId, Set<VersionMetadataField> issueFields) {

  public VersionReviewQueueCommand {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    issueFields = Set.copyOf(issueFields);
    if (issueFields.isEmpty()) {
      throw new IllegalArgumentException("review queue command must identify an issue");
    }
  }

  public static VersionReviewQueueCommand from(
      final UUID documentId, final UUID documentVersionId, final VersionMetadataResult metadata) {
    Objects.requireNonNull(metadata, "metadata");
    if (metadata.status() != VersionMetadataStatus.UNKNOWN) {
      throw new IllegalArgumentException("only UNKNOWN metadata can enter the review queue");
    }
    return new VersionReviewQueueCommand(documentId, documentVersionId, metadata.issueFields());
  }
}
