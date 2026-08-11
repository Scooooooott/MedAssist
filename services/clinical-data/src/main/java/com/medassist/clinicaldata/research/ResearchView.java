package com.medassist.clinicaldata.research;

public enum ResearchView {
  CONDITION_COUNTS("clinical_research_condition_counts"),
  OBSERVATION_COUNTS("clinical_research_observation_counts"),
  ENCOUNTER_COUNTS("clinical_research_encounter_counts");

  private final String sqlName;

  ResearchView(final String sqlName) {
    this.sqlName = sqlName;
  }

  public String sqlName() {
    return sqlName;
  }
}
