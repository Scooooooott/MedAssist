package com.medassist.auditgovernance.transport;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.CanonicalAuditEventSerializer;
import java.util.Objects;

public final class AuditEventValidator {
  public void validateForTransport(final AuditEvent event) {
    Objects.requireNonNull(event, "event");
    final String expectedPayloadHash = CanonicalAuditEventSerializer.hashPayload(event.payload());
    if (!expectedPayloadHash.equals(event.payloadHash())) {
      throw new InvalidAuditEventException("audit payload hash does not match safe metadata");
    }
    if (!event.previousHash().isEmpty() || !event.hash().isEmpty()) {
      throw new InvalidAuditEventException("transport event must not provide hash-chain fields");
    }
  }
}
