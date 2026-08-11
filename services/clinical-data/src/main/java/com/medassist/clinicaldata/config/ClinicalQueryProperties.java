package com.medassist.clinicaldata.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clinical-data.query")
public record ClinicalQueryProperties(
    int kAnonymity,
    long statementTimeoutMs,
    int maxRows,
    boolean failClosed,
    Set<String> allowedViews) {
  public ClinicalQueryProperties {
    if (kAnonymity < 2) {
      throw new IllegalArgumentException("k-anonymity must be at least 2");
    }
    if (statementTimeoutMs <= 0 || maxRows <= 0) {
      throw new IllegalArgumentException("query timeout and row limit must be positive");
    }
    if (!failClosed) {
      throw new IllegalArgumentException("clinical-data.query.fail-closed must remain true");
    }
    allowedViews = Set.copyOf(allowedViews == null ? Set.of() : allowedViews);
  }

  public static ClinicalQueryProperties defaults() {
    return new ClinicalQueryProperties(
        5,
        5_000,
        100,
        true,
        Set.of(
            "clinical_research_condition_counts",
            "clinical_research_observation_counts",
            "clinical_research_encounter_counts"));
  }
}
