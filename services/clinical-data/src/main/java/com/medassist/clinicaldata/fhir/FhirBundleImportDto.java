package com.medassist.clinicaldata.fhir;

import java.util.Objects;

/** Import input only; raw FHIR is parsed and is never placed in a clinical record. */
public record FhirBundleImportDto(String sourceId, String payload, FhirPayloadFormat format) {
  public FhirBundleImportDto {
    requireText(sourceId, "sourceId");
    requireText(payload, "payload");
    Objects.requireNonNull(format, "format");
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
