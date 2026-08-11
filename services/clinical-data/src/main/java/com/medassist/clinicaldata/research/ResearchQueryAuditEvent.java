package com.medassist.clinicaldata.research;

import com.medassist.domain.Role;
import java.time.Instant;
import java.util.Objects;

/** Audit metadata deliberately excludes query result data and filter values. */
public record ResearchQueryAuditEvent(
    Instant timestamp,
    String actor,
    Role role,
    ResearchView view,
    String outcome,
    int returnedRows,
    int suppressedGroups,
    boolean clinicalExemption,
    String exemptionReason) {
  public ResearchQueryAuditEvent {
    Objects.requireNonNull(timestamp, "timestamp");
    requireText(actor, "actor");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(view, "view");
    requireText(outcome, "outcome");
    if (returnedRows < 0 || suppressedGroups < 0) {
      throw new IllegalArgumentException("audit counts cannot be negative");
    }
    if (clinicalExemption && (exemptionReason == null || exemptionReason.isBlank())) {
      throw new IllegalArgumentException("clinical exemption requires an audit reason");
    }
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
