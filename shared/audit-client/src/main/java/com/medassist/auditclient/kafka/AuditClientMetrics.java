package com.medassist.auditclient.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

/** Low-cardinality publication and outbox metrics shared by all Java services. */
public final class AuditClientMetrics {
  private final Counter persisted;
  private final Counter delivered;
  private final Counter deliveryFailures;
  private final Counter rejected;
  private final AtomicInteger outboxDepth = new AtomicInteger();

  public AuditClientMetrics(final MeterRegistry registry) {
    persisted = registry.counter("medassist.audit.client.persisted");
    delivered = registry.counter("medassist.audit.client.delivered");
    deliveryFailures = registry.counter("medassist.audit.client.delivery.failures");
    rejected = registry.counter("medassist.audit.client.rejected");
    Gauge.builder("medassist.audit.client.outbox.depth", outboxDepth, AtomicInteger::get)
        .register(registry);
  }

  void recordPersisted(final int depth) {
    persisted.increment();
    setDepth(depth);
  }

  void recordDelivered(final int depth) {
    delivered.increment();
    setDepth(depth);
  }

  void recordDeliveryFailure(final int depth) {
    deliveryFailures.increment();
    setDepth(depth);
  }

  void recordRejected(final int depth) {
    rejected.increment();
    setDepth(depth);
  }

  public void setDepth(final int depth) {
    outboxDepth.set(depth);
  }
}
