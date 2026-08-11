package com.medassist.ingestion.pipeline.store;

import java.util.Objects;
import java.util.UUID;

/** Explicit logical-document identity supplied by the verified ingestion plan. */
public record DocumentIdentity(
    UUID logicalDocumentId,
    String sourceSystem,
    String sourceUri,
    String docType,
    String publisher,
    String title) {
  public DocumentIdentity {
    Objects.requireNonNull(logicalDocumentId, "logicalDocumentId");
    sourceSystem = requireText(sourceSystem, "sourceSystem");
    sourceUri = requireText(sourceUri, "sourceUri");
    docType = requireText(docType, "docType");
    publisher = requireText(publisher, "publisher");
    title = requireText(title, "title");
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
