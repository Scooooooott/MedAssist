package com.medassist.identitypolicy;

import java.util.Objects;

public record Obligation(String code) {
  public Obligation {
    if (Objects.requireNonNull(code, "code").isBlank()) {
      throw new IllegalArgumentException("obligation code is required");
    }
  }

  public static Obligation of(final String code) {
    return new Obligation(code);
  }
}
