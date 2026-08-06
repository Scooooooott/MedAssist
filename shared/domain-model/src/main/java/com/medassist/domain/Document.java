package com.medassist.domain;

import java.util.Objects;
import java.util.UUID;

public record Document(
    UUID id,
    String sourceSystem,
    String sourceUri,
    DocType docType,
    String publisher,
    String title) {
  public Document {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sourceSystem, "sourceSystem");
    Objects.requireNonNull(sourceUri, "sourceUri");
    Objects.requireNonNull(docType, "docType");
    Objects.requireNonNull(publisher, "publisher");
    Objects.requireNonNull(title, "title");
  }
}
