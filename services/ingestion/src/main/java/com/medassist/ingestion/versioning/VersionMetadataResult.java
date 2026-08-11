package com.medassist.ingestion.versioning;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Dependency-free extraction result. A null value means that the corresponding value was not
 * confirmed and must not be inferred by a later adapter.
 */
public record VersionMetadataResult(
    VersionMetadataStatus status,
    String publisher,
    String version,
    LocalDate effectiveDate,
    Set<VersionMetadataField> missingFields,
    Set<VersionMetadataField> invalidFields) {

  public VersionMetadataResult {
    Objects.requireNonNull(status, "status");
    missingFields = immutableFields(missingFields);
    invalidFields = immutableFields(invalidFields);
    if (status == VersionMetadataStatus.CONFIRMED) {
      Objects.requireNonNull(publisher, "publisher");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(effectiveDate, "effectiveDate");
    }
    if (status == VersionMetadataStatus.CONFIRMED
        && (!missingFields.isEmpty() || !invalidFields.isEmpty())) {
      throw new IllegalArgumentException("confirmed metadata cannot contain unresolved fields");
    }
    if (status == VersionMetadataStatus.UNKNOWN
        && missingFields.isEmpty()
        && invalidFields.isEmpty()) {
      throw new IllegalArgumentException("unknown metadata must identify an unresolved field");
    }
  }

  public Set<VersionMetadataField> issueFields() {
    final EnumSet<VersionMetadataField> issues = EnumSet.noneOf(VersionMetadataField.class);
    issues.addAll(missingFields);
    issues.addAll(invalidFields);
    return Set.copyOf(issues);
  }

  private static Set<VersionMetadataField> immutableFields(final Set<VersionMetadataField> fields) {
    Objects.requireNonNull(fields, "fields");
    return Set.copyOf(fields);
  }
}
