package com.medassist.auditgovernance;

import java.util.List;
import java.util.UUID;

/** Authoritative append-only audit chain and processed-event index. */
public interface AuditChainStore extends AuditEventPublisher {
  List<AuditEvent> events();

  AuditChainVerificationResult verify();

  String lastHash();

  void anchor(AuditChainAnchor anchor);

  boolean contains(UUID eventId);

  /**
   * Confirms that an event is durable in this chain; it never writes a second deduplication log.
   */
  void markProcessed(UUID eventId);
}
