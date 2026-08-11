package com.medassist.agent.security;

import java.util.Objects;

public record SqlValidationResult(boolean allowed, SqlViolation violation, String sqlHash) {
  public SqlValidationResult {
    Objects.requireNonNull(violation, "violation");
    Objects.requireNonNull(sqlHash, "sqlHash");
  }

  public String reasonCode() {
    return violation.name();
  }

  public String reasonMessage() {
    return violation.message();
  }
}
