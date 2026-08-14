package com.medassist.common.resilience;

import java.util.Objects;

/** User-visible, audit-ready degradation without request or result text. */
public record Degradation(
    String code, String affectedStage, FallbackMode fallbackMode, String reason) {
  private static final int MAX_REASON_LENGTH = 160;

  public Degradation {
    code = requireIdentifier(code, "code");
    affectedStage = requireIdentifier(affectedStage, "affectedStage");
    Objects.requireNonNull(fallbackMode, "fallbackMode");
    if (fallbackMode == FallbackMode.NONE) {
      throw new IllegalArgumentException("a degradation must identify an explicit fallback");
    }
    if (reason == null
        || reason.isBlank()
        || reason.length() > MAX_REASON_LENGTH
        || reason.indexOf('\n') >= 0
        || reason.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("reason must be a short, single-line safe description");
    }
  }

  private static String requireIdentifier(final String value, final String name) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException(name + " must be an uppercase stable identifier");
    }
    return value;
  }
}
