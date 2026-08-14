package com.medassist.auditgovernance.transport;

import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

public final class MeteredAuditDeadLetterRecoverer implements ConsumerRecordRecoverer {
  private final ConsumerRecordRecoverer delegate;
  private final AuditTransportMetrics metrics;

  public MeteredAuditDeadLetterRecoverer(
      final ConsumerRecordRecoverer delegate, final AuditTransportMetrics metrics) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public void accept(final ConsumerRecord<?, ?> record, final Exception exception) {
    delegate.accept(record, exception);
    metrics.recordDlqRouted();
  }
}
