package com.medassist.auditgovernance;

import java.util.List;

public final class HashChainVerifier implements AuditChainVerifier {
  @Override
  public AuditChainVerificationResult verify(final List<AuditEvent> events) {
    if (events == null) {
      return AuditChainVerificationResult.broken(1, null, 0, "event list is null");
    }
    String expectedPrevious = "";
    for (int index = 0; index < events.size(); index++) {
      final AuditEvent event = events.get(index);
      final long sequence = index + 1L;
      if (event == null) {
        return AuditChainVerificationResult.broken(sequence, null, index, "event is null");
      }
      if (!expectedPrevious.equals(event.previousHash())) {
        return AuditChainVerificationResult.broken(
            sequence, event.eventId(), index, "previous hash does not match");
      }
      final String expectedHash = CanonicalAuditEventSerializer.hash(event);
      if (!expectedHash.equals(event.hash())) {
        return AuditChainVerificationResult.broken(
            sequence, event.eventId(), index, "event hash does not match");
      }
      expectedPrevious = event.hash();
    }
    return AuditChainVerificationResult.valid(events.size());
  }
}
