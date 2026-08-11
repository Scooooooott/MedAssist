package com.medassist.auditgovernance;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public record AuditChainVerificationResult(
    boolean valid,
    long checkedEvents,
    OptionalLong brokenSequence,
    Optional<UUID> brokenEventId,
    String reason) {
  public static AuditChainVerificationResult valid(final long checkedEvents) {
    return new AuditChainVerificationResult(
        true, checkedEvents, OptionalLong.empty(), Optional.empty(), "chain is intact");
  }

  public static AuditChainVerificationResult broken(
      final long sequence, final UUID eventId, final long checkedEvents, final String reason) {
    return new AuditChainVerificationResult(
        false, checkedEvents, OptionalLong.of(sequence), Optional.ofNullable(eventId), reason);
  }

  public OptionalLong brokenAt() {
    return brokenSequence;
  }
}
