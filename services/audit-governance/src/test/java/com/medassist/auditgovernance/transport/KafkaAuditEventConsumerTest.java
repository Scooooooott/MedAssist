package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditPayload;
import com.medassist.auditgovernance.InMemoryAuditEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.Acknowledgment;

class KafkaAuditEventConsumerTest {
  @Test
  void duplicateDeliveryIsAcknowledgedButAppendedOnlyOnce() {
    final TestHarness harness = new TestHarness();
    final AuditEvent event = event(1);
    final ConsumerRecord<String, byte[]> record = harness.record(event, 0);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);

    harness.consumer.consume(record, acknowledgment, null);
    harness.consumer.consume(record, acknowledgment, null);

    assertThat(harness.chain.events()).hasSize(1);
    assertThat(harness.chain.verify().valid()).isTrue();
    verify(acknowledgment, times(2)).acknowledge();
    assertThat(harness.registry.get("medassist.audit.events.processed").counter().count())
        .isEqualTo(1);
    assertThat(harness.registry.get("medassist.audit.events.duplicate").counter().count())
        .isEqualTo(1);
  }

  @Test
  void oneHundredConcurrentConsumedEventsProduceAnIntactChain() throws Exception {
    final TestHarness harness = new TestHarness();
    final int eventCount = 100;
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(eventCount);
    final List<Future<?>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < eventCount; index++) {
        final int eventIndex = index;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  final AuditEvent event = event(eventIndex + 1);
                  harness.consumer.consume(
                      harness.record(event, eventIndex), mock(Acknowledgment.class), null);
                  return null;
                }));
      }
      start.countDown();
      for (final Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(harness.chain.events()).hasSize(eventCount);
    assertThat(harness.chain.verify().valid()).isTrue();
    assertThat(harness.registry.get("medassist.audit.events.processed").counter().count())
        .isEqualTo(eventCount);
  }

  @Test
  void poisonMessageIsNotAcknowledgedAndRecovererRecordsDlqRouting() {
    final TestHarness harness = new TestHarness();
    final ConsumerRecord<String, byte[]> poison =
        new ConsumerRecord<>("audit-events", 0, 7, "not-an-event-id", new byte[] {(byte) 0x80});
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);

    assertThatThrownBy(() -> harness.consumer.consume(poison, acknowledgment, null))
        .isInstanceOf(InvalidAuditEventException.class);
    verify(acknowledgment, never()).acknowledge();

    final ConsumerRecordRecoverer dlqDelegate = mock(ConsumerRecordRecoverer.class);
    final MeteredAuditDeadLetterRecoverer recoverer =
        new MeteredAuditDeadLetterRecoverer(dlqDelegate, harness.metrics);
    final InvalidAuditEventException failure = new InvalidAuditEventException("poison");
    recoverer.accept(poison, failure);

    verify(dlqDelegate).accept(poison, failure);
    assertThat(harness.registry.get("medassist.audit.dlq.routed").counter().count()).isEqualTo(1);
    assertThat(harness.registry.get("medassist.audit.dlq.pending").gauge().value()).isEqualTo(0);
    harness.metrics.setDlqPending(3);
    assertThat(harness.registry.get("medassist.audit.dlq.pending").gauge().value()).isEqualTo(3);
    harness.metrics.setDlqPending(-1);
    assertThat(harness.registry.get("medassist.audit.dlq.pending").gauge().value()).isEqualTo(0);
  }

  private static AuditEvent event(final int index) {
    return new AuditEvent(
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index),
        "service-a",
        "CLINICIAN",
        "READ",
        "CLINICAL_DATA",
        "resource-" + index,
        "ALLOWED",
        AuditPayload.of(Map.of("entityCount", Integer.toString(index))));
  }

  private static final class TestHarness {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditTransportMetrics metrics = new AuditTransportMetrics(registry);
    private final AuditEventCodec codec = new AuditEventCodec(new AuditEventValidator());
    private final InMemoryAuditEventPublisher chain = new InMemoryAuditEventPublisher();
    private final KafkaAuditEventConsumer consumer =
        new KafkaAuditEventConsumer(
            codec, new AuditEventProcessor(new AuditEventValidator(), chain, metrics), metrics);

    private ConsumerRecord<String, byte[]> record(final AuditEvent event, final long offset) {
      return new ConsumerRecord<>(
          "audit-events", 0, offset, event.eventId().toString(), codec.encode(event));
    }
  }
}
