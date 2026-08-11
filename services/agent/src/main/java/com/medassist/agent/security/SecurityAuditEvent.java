package com.medassist.agent.security;

import java.util.Objects;
import java.util.Set;

public record SecurityAuditEvent(
    String action,
    String destination,
    ContentClass contentClass,
    EgressSource source,
    EgressReason reason,
    String payloadHash,
    Set<SensitiveFinding> sensitiveFindings) {
  public SecurityAuditEvent {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(contentClass, "contentClass");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(payloadHash, "payloadHash");
    Objects.requireNonNull(sensitiveFindings, "sensitiveFindings");
    sensitiveFindings = Set.copyOf(sensitiveFindings);
  }

  public boolean containsPayload() {
    return false;
  }
}
