package com.medassist.auditgovernance.transport;

import com.medassist.auditgovernance.AuditChainStore;
import com.medassist.auditgovernance.AuditEvent;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes deduplication and append so one global hash-chain order is preserved. */
public final class AuditEventProcessor {
  public enum Result {
    PROCESSED,
    DUPLICATE
  }

  private final ReentrantLock processingLock = new ReentrantLock();
  private final AuditEventValidator validator;
  private final AuditChainStore chainStore;
  private final AuditTransportMetrics metrics;

  public AuditEventProcessor(
      final AuditEventValidator validator,
      final AuditChainStore chainStore,
      final AuditTransportMetrics metrics) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.chainStore = Objects.requireNonNull(chainStore, "chainStore");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  public Result process(final AuditEvent event) {
    validator.validateForTransport(event);
    processingLock.lock();
    try {
      if (chainStore.contains(event.eventId())) {
        metrics.recordDuplicate();
        return Result.DUPLICATE;
      }
      chainStore.append(event);
      chainStore.markProcessed(event.eventId());
      metrics.recordProcessed();
      return Result.PROCESSED;
    } finally {
      processingLock.unlock();
    }
  }
}
