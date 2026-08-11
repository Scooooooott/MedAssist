package com.medassist.ingestion.pipeline.index;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Application-layer document record ready for a later JDBC adapter. */
public record IndexableDocument(
    UUID logicalDocumentId,
    UUID documentVersionId,
    String sourceId,
    String title,
    String publisher,
    String deidentificationPolicyVersion,
    Map<String, String> metadata) {
  public IndexableDocument {
    Objects.requireNonNull(logicalDocumentId, "logicalDocumentId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    sourceId = requireText(sourceId, "sourceId");
    title = requireText(title, "title");
    publisher = requireText(publisher, "publisher");
    deidentificationPolicyVersion =
        requireText(deidentificationPolicyVersion, "deidentificationPolicyVersion");
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
