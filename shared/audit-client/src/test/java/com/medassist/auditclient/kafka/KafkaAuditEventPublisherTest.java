package com.medassist.auditclient.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.auditclient.SafeAuditEvent;
import com.medassist.auditclient.TestEvents;
import com.medassist.auditclient.outbox.AuditOutboxFullException;
import com.medassist.auditclient.outbox.InMemoryAuditOutbox;
import com.medassist.auditclient.proto.AuditEventProtoCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaAuditEventPublisherTest {
  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider tracerProvider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
  private final OpenTelemetry telemetry =
      OpenTelemetrySdk.builder()
          .setTracerProvider(tracerProvider)
          .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
          .build();

  @AfterEach
  void closeTracing() {
    tracerProvider.close();
  }

  @Test
  void persistsBeforeSendAndAcknowledgesOnlyAfterAsyncSuccess() {
    final InMemoryAuditOutbox outbox = new InMemoryAuditOutbox(4);
    final CompletableFuture<SendResult<String, byte[]>> future = new CompletableFuture<>();
    final AtomicBoolean persistedBeforeSend = new AtomicBoolean();
    final KafkaTemplate<String, byte[]> template = template();
    when(template.send(anyRecord()))
        .thenAnswer(
            invocation -> {
              persistedBeforeSend.set(outbox.size() == 1);
              return future;
            });
    final KafkaAuditEventPublisher publisher = publisher(template, outbox, "audit-events");
    final SafeAuditEvent event = TestEvents.event();

    publisher.publish(event);

    assertThat(persistedBeforeSend).isTrue();
    assertThat(outbox.size()).isEqualTo(1);
    final ArgumentCaptor<ProducerRecord<String, byte[]>> recordCaptor = recordCaptor();
    verify(template).send(recordCaptor.capture());
    assertThat(recordCaptor.getValue().key()).isEqualTo(event.eventId().toString());
    assertThat(recordCaptor.getValue().topic()).isEqualTo("audit-events");
    assertThat(new AuditEventProtoCodec().decode(recordCaptor.getValue().value())).isEqualTo(event);

    future.complete(sendResult());

    assertThat(outbox.size()).isZero();
  }

  @Test
  void asyncFailureRetainsMessageForScheduledDrain() {
    final InMemoryAuditOutbox outbox = new InMemoryAuditOutbox(4);
    final CompletableFuture<SendResult<String, byte[]>> future = new CompletableFuture<>();
    final CompletableFuture<SendResult<String, byte[]>> retry =
        CompletableFuture.completedFuture(sendResult());
    final KafkaTemplate<String, byte[]> template = template();
    when(template.send(anyRecord())).thenReturn(future, retry);
    final KafkaAuditEventPublisher publisher = publisher(template, outbox, "audit-events");

    publisher.publish(TestEvents.event());
    future.completeExceptionally(new IllegalStateException("broker unavailable"));

    assertThat(outbox.size()).isEqualTo(1);
    assertThat(outbox.peek()).isPresent();
    assertThat(
            exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("messaging.publish.audit-events"))
                .findFirst()
                .orElseThrow()
                .getStatus()
                .getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);

    publisher.drain();

    assertThat(outbox.size()).isZero();
    verify(template, times(2)).send(anyRecord());
  }

  @Test
  void fullOutboxThrowsSynchronouslyAndDoesNotAttemptAnotherSend() {
    final InMemoryAuditOutbox outbox = new InMemoryAuditOutbox(1);
    final KafkaTemplate<String, byte[]> template = template();
    final CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("broker unavailable"));
    when(template.send(anyRecord())).thenReturn(failed);
    final KafkaAuditEventPublisher publisher = publisher(template, outbox, "audit-events");
    publisher.publish(TestEvents.event());

    assertThatThrownBy(() -> publisher.publish(TestEvents.event(UUID.randomUUID())))
        .isInstanceOf(AuditOutboxFullException.class);
    verify(template, times(1)).send(anyRecord());
    assertThat(outbox.size()).isEqualTo(1);
  }

  @Test
  void injectsProducerW3cHeaderAndCreatesChildProducerSpanWithoutSensitiveAttributes() {
    final InMemoryAuditOutbox outbox = new InMemoryAuditOutbox(4);
    final KafkaTemplate<String, byte[]> template = template();
    when(template.send(anyRecord())).thenReturn(CompletableFuture.completedFuture(sendResult()));
    final KafkaAuditEventPublisher publisher = publisher(template, outbox, "configured-audit");
    final Span parent = telemetry.getTracer("test").spanBuilder("parent").startSpan();

    try (Scope ignored = parent.makeCurrent()) {
      publisher.publish(TestEvents.event());
    } finally {
      parent.end();
    }

    final ArgumentCaptor<ProducerRecord<String, byte[]>> recordCaptor = recordCaptor();
    verify(template).send(recordCaptor.capture());
    final ProducerRecord<String, byte[]> record = recordCaptor.getValue();
    final String traceparent =
        new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
    final SpanData producer =
        exporter.getFinishedSpanItems().stream()
            .filter(span -> span.getName().equals("messaging.publish.audit-events"))
            .findFirst()
            .orElseThrow();

    assertThat(producer.getKind()).isEqualTo(SpanKind.PRODUCER);
    assertThat(producer.getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
    assertThat(producer.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    assertThat(traceparent).contains(producer.getSpanId());
    assertThat(producer.getAttributes().asMap().keySet())
        .extracting(AttributeKey::getKey)
        .containsExactlyInAnyOrder(
            "messaging.system", "messaging.destination.name", "messaging.operation.name")
        .noneMatch(key -> key.contains("query") || key.contains("text"));
  }

  private KafkaAuditEventPublisher publisher(
      final KafkaTemplate<String, byte[]> template,
      final InMemoryAuditOutbox outbox,
      final String topic) {
    return new KafkaAuditEventPublisher(
        template,
        topic,
        new AuditEventProtoCodec(),
        outbox,
        new AuditClientMetrics(new SimpleMeterRegistry()),
        telemetry,
        W3CTraceContextPropagator.getInstance(),
        Duration.ofSeconds(5));
  }

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, byte[]> template() {
    return mock(KafkaTemplate.class);
  }

  @SuppressWarnings("unchecked")
  private static SendResult<String, byte[]> sendResult() {
    return mock(SendResult.class);
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<ProducerRecord<String, byte[]>> recordCaptor() {
    return ArgumentCaptor.forClass(ProducerRecord.class);
  }

  private static ProducerRecord<String, byte[]> anyRecord() {
    return any();
  }
}
