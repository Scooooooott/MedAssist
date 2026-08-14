package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.medassist.auditgovernance.AuditChainAnchor;
import com.medassist.auditgovernance.AuditChainStore;
import com.medassist.auditgovernance.AuditChainVerificationResult;
import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditPayload;
import com.medassist.auditgovernance.InMemoryAuditEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class KafkaAuditEventConsumerTracingTest {
  private static final String REMOTE_TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String REMOTE_SPAN_ID = "0123456789abcdef";

  private final RecordingExporter exporter = new RecordingExporter();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
  private final OpenTelemetry telemetry =
      OpenTelemetrySdk.builder()
          .setTracerProvider(provider)
          .setPropagators(
              ContextPropagators.create(
                  TextMapPropagator.composite(
                      W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
          .build();

  @AfterEach
  void closeProvider() {
    provider.close();
  }

  @Test
  void extractsTraceStateAndBaggageAndCreatesConsumerChildSpan() {
    final RecordingChainStore chain = new RecordingChainStore();
    final Harness harness = new Harness(chain, telemetry);
    final AuditEvent event = event();
    final ConsumerRecord<String, byte[]> record = harness.record(event, 0);
    final Span remoteParent =
        Span.wrap(
            SpanContext.createFromRemoteParent(
                REMOTE_TRACE_ID,
                REMOTE_SPAN_ID,
                TraceFlags.getSampled(),
                TraceState.builder().put("vendor", "state").build()));
    final Context propagated =
        Baggage.builder()
            .put("test-baggage", "present")
            .build()
            .storeInContext(Context.root().with(remoteParent));
    telemetry
        .getPropagators()
        .getTextMapPropagator()
        .inject(
            propagated,
            record.headers(),
            (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8)));

    harness.consumer.consume(record, mock(Acknowledgment.class), null);

    final SpanData consumerSpan = exporter.named("audit-events.consume");
    assertThat(consumerSpan.getKind()).isEqualTo(SpanKind.CONSUMER);
    assertThat(consumerSpan.getTraceId()).isEqualTo(REMOTE_TRACE_ID);
    assertThat(consumerSpan.getParentSpanId()).isEqualTo(REMOTE_SPAN_ID);
    assertThat(consumerSpan.getSpanContext().getTraceState().get("vendor")).isEqualTo("state");
    assertThat(chain.observedSpanId).isEqualTo(consumerSpan.getSpanId());
    assertThat(chain.observedBaggage).isEqualTo("present");
    assertThat(consumerSpan.getAttributes().asMap().keySet())
        .extracting(AttributeKey::getKey)
        .containsExactlyInAnyOrder(
            "messaging.system",
            "messaging.destination.name",
            "messaging.operation.name",
            "messaging.destination.partition.id");
  }

  @Test
  void processingFailureEndsConsumerSpanWithError() {
    final Harness harness = new Harness(new RecordingChainStore(), telemetry);
    final AuditEvent event = event();
    final ConsumerRecord<String, byte[]> record = harness.record(event, 1);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);

    assertThatThrownBy(() -> harness.consumer.consume(record, acknowledgment, null))
        .isInstanceOf(InvalidAuditEventException.class);

    final SpanData consumerSpan = exporter.named("audit-events.consume");
    assertThat(consumerSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(consumerSpan.getEvents())
        .anyMatch(eventData -> eventData.getName().equals("exception"));
    verify(acknowledgment, never()).acknowledge();
  }

  private static AuditEvent event() {
    return new AuditEvent(
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z"),
        "source-service",
        "SYSTEM",
        "AUDIT",
        "audit_event",
        "resource-1",
        "ALLOWED",
        AuditPayload.of(Map.of("entityCount", "1")));
  }

  private static final class Harness {
    private final AuditEventCodec codec = new AuditEventCodec(new AuditEventValidator());
    private final KafkaAuditEventConsumer consumer;

    private Harness(final AuditChainStore chainStore, final OpenTelemetry telemetry) {
      final AuditTransportMetrics metrics = new AuditTransportMetrics(new SimpleMeterRegistry());
      consumer =
          new KafkaAuditEventConsumer(
              codec,
              new AuditEventProcessor(new AuditEventValidator(), chainStore, metrics),
              metrics,
              telemetry);
    }

    private ConsumerRecord<String, byte[]> record(final AuditEvent event, final int partition) {
      return new ConsumerRecord<>(
          "audit-events", partition, 0, event.eventId().toString(), codec.encode(event));
    }
  }

  private static final class RecordingChainStore implements AuditChainStore {
    private final InMemoryAuditEventPublisher delegate = new InMemoryAuditEventPublisher();
    private String observedSpanId;
    private String observedBaggage;

    @Override
    public AuditEvent publish(final AuditEvent event) {
      observedSpanId = Span.current().getSpanContext().getSpanId();
      observedBaggage = Baggage.current().getEntryValue("test-baggage");
      return delegate.publish(event);
    }

    @Override
    public List<AuditEvent> events() {
      return delegate.events();
    }

    @Override
    public AuditChainVerificationResult verify() {
      return delegate.verify();
    }

    @Override
    public String lastHash() {
      return delegate.lastHash();
    }

    @Override
    public void anchor(final AuditChainAnchor anchor) {
      delegate.anchor(anchor);
    }

    @Override
    public boolean contains(final UUID eventId) {
      return delegate.contains(eventId);
    }

    @Override
    public void markProcessed(final UUID eventId) {
      delegate.markProcessed(eventId);
    }
  }

  private static final class RecordingExporter implements SpanExporter {
    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(final Collection<SpanData> exported) {
      spans.addAll(exported);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    private SpanData named(final String name) {
      return spans.stream().filter(span -> span.getName().equals(name)).findFirst().orElseThrow();
    }
  }
}
