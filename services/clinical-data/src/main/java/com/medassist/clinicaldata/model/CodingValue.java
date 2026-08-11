package com.medassist.clinicaldata.model;

import java.util.Objects;

/** A terminology coding retained verbatim after Safe Harbor mapping. */
public record CodingValue(String system, String code, String display) {
  public CodingValue {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("coding code is required");
    }
  }

  public CodingValue requireSystem(final String expectedSystem) {
    Objects.requireNonNull(expectedSystem, "expectedSystem");
    if (!expectedSystem.equals(system)) {
      throw new IllegalArgumentException("unexpected coding system");
    }
    return this;
  }
}
