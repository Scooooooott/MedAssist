package com.medassist.clinicaldata.query;

public enum StructuredView {
  CONDITION_COUNTS("clinical_research_condition_counts"),
  OBSERVATION_COUNTS("clinical_research_observation_counts"),
  ENCOUNTER_COUNTS("clinical_research_encounter_counts");

  private final String sqlName;

  StructuredView(final String sqlName) {
    this.sqlName = sqlName;
  }

  public String sqlName() {
    return sqlName;
  }
}
