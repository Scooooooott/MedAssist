package com.medassist.clinicaldata.fhir;

import java.util.Objects;

public record FhirValidationIssue(String code, String message) {
  public FhirValidationIssue {
    if (Objects.requireNonNull(code, "code").isBlank()) {
      throw new IllegalArgumentException("validation issue code is required");
    }
    if (Objects.requireNonNull(message, "message").isBlank()) {
      throw new IllegalArgumentException("validation issue message is required");
    }
  }
}
