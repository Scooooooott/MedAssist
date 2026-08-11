package com.medassist.clinicaldata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clinical-data.import")
public record ClinicalImportProperties(boolean requireProfile, String profileBaseUrl) {
  public ClinicalImportProperties {
    if (profileBaseUrl == null || profileBaseUrl.isBlank()) {
      throw new IllegalArgumentException("clinical-data.import.profile-base-url is required");
    }
  }

  public static ClinicalImportProperties defaults() {
    return new ClinicalImportProperties(
        true, "https://medassist.example/fhir/StructureDefinition/");
  }
}
