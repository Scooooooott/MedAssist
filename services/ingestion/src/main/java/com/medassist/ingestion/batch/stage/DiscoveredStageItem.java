package com.medassist.ingestion.batch.stage;

import com.medassist.ingestion.discovery.DiscoveryClassification;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Safe discovery metadata required to restart processing without persisting source content. */
public record DiscoveredStageItem(
    UUID ingestionRunId,
    UUID logicalDocumentId,
    UUID documentVersionId,
    URI sourceUri,
    String sourceId,
    String mimeType,
    long sizeBytes,
    String contentHash,
    String previousContentHash,
    DiscoveryClassification classification,
    Map<String, String> safeObjectMetadata,
    boolean forceReprocess) {

  public DiscoveredStageItem {
    Objects.requireNonNull(ingestionRunId, "ingestionRunId");
    Objects.requireNonNull(logicalDocumentId, "logicalDocumentId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(sourceUri, "sourceUri");
    if (!sourceUri.isAbsolute()) {
      throw new IllegalArgumentException("sourceUri must be absolute");
    }
    sourceId = requireText(sourceId, "sourceId");
    mimeType = requireText(mimeType, "mimeType");
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must be non-negative");
    }
    contentHash = requireText(contentHash, "contentHash");
    if (previousContentHash != null && previousContentHash.isBlank()) {
      throw new IllegalArgumentException("previousContentHash must be null or non-blank");
    }
    Objects.requireNonNull(classification, "classification");
    safeObjectMetadata =
        Map.copyOf(Objects.requireNonNull(safeObjectMetadata, "safeObjectMetadata"));
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
