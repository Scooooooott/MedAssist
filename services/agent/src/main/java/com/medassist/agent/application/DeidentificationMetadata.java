package com.medassist.agent.application;

import com.medassist.domain.Role;
import java.util.Objects;

/** Non-sensitive request metadata sent alongside an ingress de-identification request. */
public record DeidentificationMetadata(String traceId, String requestId, Role role) {
  public DeidentificationMetadata {
    requireText(traceId, "traceId");
    requireText(requestId, "requestId");
    Objects.requireNonNull(role, "role");
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
