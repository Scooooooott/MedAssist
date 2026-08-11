package com.medassist.clinicaldata.query;

import com.medassist.domain.Role;
import java.util.Objects;

public record StructuredQueryRequest(
    String actor,
    Role role,
    StructuredView view,
    String sql,
    boolean clinicalExemption,
    String exemptionReason) {
  public StructuredQueryRequest {
    requireText(actor, "actor");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(view, "view");
    requireText(sql, "sql");
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
