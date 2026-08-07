package com.medassist.domain;

/**
 * Column-level data sensitivity. This is orthogonal to {@link ContentDomain} and drives grants and
 * row-level security policy generation.
 */
public enum ColumnClassification {
  PHI_DIRECT,
  PHI_QUASI,
  CLINICAL_FIELD,
  PUBLIC_FIELD
}
