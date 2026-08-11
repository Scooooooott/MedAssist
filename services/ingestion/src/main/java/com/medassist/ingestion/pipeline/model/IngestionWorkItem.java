package com.medassist.ingestion.pipeline.model;

import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import java.util.Objects;
import java.util.UUID;

/** Immutable identity and discovery input for one document version. */
public record IngestionWorkItem(
    ObjectDiscoveryResult discoveryResult, UUID logicalDocumentId, UUID documentVersionId) {

  public IngestionWorkItem {
    Objects.requireNonNull(discoveryResult, "discoveryResult must not be null");
    Objects.requireNonNull(logicalDocumentId, "logicalDocumentId must not be null");
    Objects.requireNonNull(documentVersionId, "documentVersionId must not be null");
  }
}
