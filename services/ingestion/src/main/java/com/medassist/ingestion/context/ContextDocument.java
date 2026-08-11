package com.medassist.ingestion.context;

import java.util.Objects;
import java.util.UUID;

/** Document-level fields shared by all chunk context requests. */
public record ContextDocument(
    UUID documentVersionId, String title, String publisher, String sharedDocumentSummary) {
  public ContextDocument {
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    title = requireText(title, "title");
    publisher = requireText(publisher, "publisher");
    sharedDocumentSummary = requireText(sharedDocumentSummary, "sharedDocumentSummary");
  }

  private static String requireText(final String value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
