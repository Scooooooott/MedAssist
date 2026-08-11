package com.medassist.agent.security;

import java.util.Objects;

public record EgressDecision(boolean allowed, EgressReason reason, SecurityAuditEvent auditEvent) {
  public EgressDecision {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(auditEvent, "auditEvent");
  }

  public String reasonCode() {
    return reason.name();
  }

  public String reasonMessage() {
    return reason.message();
  }
}
