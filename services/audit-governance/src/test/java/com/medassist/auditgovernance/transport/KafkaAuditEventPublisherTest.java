package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaAuditEventPublisherTest {
  @Test
  void brokerFailureBuffersWithoutBlockingTheCaller() {
    final KafkaTemplate<String, byte[]> kafkaTemplate = kafkaTemplate();
    final CompletableFuture<SendResult<String, byte[]>> failedSend = new CompletableFuture<>();
    failedSend.completeExceptionally(new IllegalStateException("broker unavailable"));
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedSend);
    final InMemoryBoundedAuditEventBuffer buffer = new InMemoryBoundedAuditEventBuffer(2);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final KafkaAuditEventPublisher publisher = publisher(kafkaTemplate, buffer, registry);
    final AuditEvent event = event(1);

    assertThat(publisher.publish(event)).isSameAs(event);

    assertThat(buffer.peek())
        .contains(new BufferedAuditMessage(event.eventId().toString(), codec().encode(event)));
    assertThat(registry.get("medassist.audit.buffered").counter().count()).isEqualTo(1);
    assertThat(registry.get("medassist.audit.buffer.depth").gauge().value()).isEqualTo(1);
  }

  @Test
  void fullBufferIsDetectedAndNeverEvictsAnOlderEvent() {
    final KafkaTemplate<String, byte[]> kafkaTemplate = kafkaTemplate();
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenThrow(new IllegalStateException("broker unavailable"));
    final InMemoryBoundedAuditEventBuffer buffer = new InMemoryBoundedAuditEventBuffer(1);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final KafkaAuditEventPublisher publisher = publisher(kafkaTemplate, buffer, registry);
    final AuditEvent first = event(1);
    publisher.publish(first);

    assertThatThrownBy(() -> publisher.publish(event(2)))
        .isInstanceOf(AuditBufferFullException.class);
    assertThat(buffer.peek().orElseThrow().eventId()).isEqualTo(first.eventId().toString());
    assertThat(registry.get("medassist.audit.buffer.rejected").counter().count()).isEqualTo(1);
  }

  private static KafkaAuditEventPublisher publisher(
      final KafkaTemplate<String, byte[]> kafkaTemplate,
      final DurableAuditEventBuffer buffer,
      final SimpleMeterRegistry registry) {
    return new KafkaAuditEventPublisher(
        kafkaTemplate,
        "audit-events",
        codec(),
        new AuditEventValidator(),
        buffer,
        new AuditTransportMetrics(registry),
        Duration.ofSeconds(1));
  }

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, byte[]> kafkaTemplate() {
    return (KafkaTemplate<String, byte[]>) mock(KafkaTemplate.class);
  }

  private static AuditEventCodec codec() {
    return new AuditEventCodec(new AuditEventValidator());
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
}
