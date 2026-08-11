package com.medassist.auditgovernance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Direct in-memory publisher for M4; its implementation can be replaced by a transport adapter. */
public final class InMemoryAuditEventPublisher implements AuditEventPublisher {
  private final ReentrantLock appendLock = new ReentrantLock();
  private final List<AuditEvent> events = new ArrayList<>();
  private final AuditChainVerifier verifier;

  public InMemoryAuditEventPublisher() {
    this(new HashChainVerifier());
  }

  public InMemoryAuditEventPublisher(final AuditChainVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  public AuditEvent publish(final AuditEvent event) {
    appendLock.lock();
    try {
      final String previousHash = events.isEmpty() ? "" : events.get(events.size() - 1).hash();
      final AuditEvent chained = event.withPreviousHash(previousHash);
      final AuditEvent sealed = chained.withHash(CanonicalAuditEventSerializer.hash(chained));
      events.add(sealed);
      return sealed;
    } finally {
      appendLock.unlock();
    }
  }

  public List<AuditEvent> events() {
    appendLock.lock();
    try {
      return List.copyOf(events);
    } finally {
      appendLock.unlock();
    }
  }

  public AuditChainVerificationResult verify() {
    return verifier.verify(events());
  }

  public String lastHash() {
    appendLock.lock();
    try {
      return events.isEmpty() ? "" : events.get(events.size() - 1).hash();
    } finally {
      appendLock.unlock();
    }
  }

  public void anchor(final AuditChainAnchor anchor) {
    appendLock.lock();
    try {
      anchor.anchor(lastHash(), events.size());
    } finally {
      appendLock.unlock();
    }
  }
}
