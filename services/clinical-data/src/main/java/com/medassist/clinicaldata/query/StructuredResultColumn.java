package com.medassist.clinicaldata.query;

import java.util.Objects;

public record StructuredResultColumn(String name, String description) {
  public StructuredResultColumn {
    if (Objects.requireNonNull(name, "name").isBlank()
        || Objects.requireNonNull(description, "description").isBlank()) {
      throw new IllegalArgumentException("result column metadata is required");
    }
  }
}
