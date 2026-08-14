package com.medassist.auditgovernance.transport;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

/** Low-cardinality transport metrics. Event content and identifiers are never metric labels. */
public final class AuditTransportMetrics {
  private final Counter processed;
  private final Counter duplicates;
  private final Counter dlqRouted;
  private final Counter buffered;
  private final Counter bufferRejected;
  private final AtomicLong consumerLag = new AtomicLong();
  private final AtomicLong dlqPending = new AtomicLong();
  private final AtomicLong bufferDepth = new AtomicLong();

  public AuditTransportMetrics(final MeterRegistry registry) {
    processed = registry.counter("medassist.audit.events.processed");
    duplicates = registry.counter("medassist.audit.events.duplicate");
    dlqRouted = registry.counter("medassist.audit.dlq.routed");
    buffered = registry.counter("medassist.audit.buffered");
    bufferRejected = registry.counter("medassist.audit.buffer.rejected");
    Gauge.builder("medassist.audit.consumer.lag", consumerLag, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("medassist.audit.dlq.pending", dlqPending, AtomicLong::doubleValue)
        .register(registry);
    Gauge.builder("medassist.audit.buffer.depth", bufferDepth, AtomicLong::doubleValue)
        .register(registry);
  }

  public void recordProcessed() {
    processed.increment();
  }

  public void recordDuplicate() {
    duplicates.increment();
  }

  public void recordDlqRouted() {
    dlqRouted.increment();
  }

  /** Updates the broker-reported number of messages awaiting DLQ handling. */
  public void setDlqPending(final long currentDepth) {
    dlqPending.set(Math.max(0, currentDepth));
  }

  public void recordBuffered(final int currentDepth) {
    buffered.increment();
    setBufferDepth(currentDepth);
  }

  public void recordBufferRejected(final int currentDepth) {
    bufferRejected.increment();
    setBufferDepth(currentDepth);
  }

  public void setBufferDepth(final int currentDepth) {
    bufferDepth.set(Math.max(0, currentDepth));
  }

  public void setConsumerLag(final long lag) {
    consumerLag.set(Math.max(0, lag));
  }
}
