package com.medassist.domain;

/**
 * Row-level content category. This is orthogonal to {@link ColumnClassification} and drives
 * retrieval filtering and tool authorization.
 */
public enum ContentDomain {
  CLINICAL,
  POLICY,
  DRUG_LABEL,
  CASE_REPORT,
  PUBLIC
}
