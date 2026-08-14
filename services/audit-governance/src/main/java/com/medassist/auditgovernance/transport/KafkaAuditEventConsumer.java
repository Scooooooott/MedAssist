package com.medassist.auditgovernance.transport;

import com.medassist.auditgovernance.AuditEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * One listener thread consumes the one-partition audit topic and acknowledges only after append.
 */
public final class KafkaAuditEventConsumer {
  private static final TextMapPropagator W3C_PROPAGATOR =
      TextMapPropagator.composite(
          W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance());
  private static final TextMapGetter<Headers> HEADER_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(final Headers carrier) {
          final List<String> keys = new ArrayList<>();
          if (carrier != null) {
            for (final Header header : carrier) {
              keys.add(header.key());
            }
          }
          return keys;
        }

        @Override
        public String get(final Headers carrier, final String key) {
          if (carrier == null) {
            return null;
          }
          final Header header = carrier.lastHeader(key);
          return header == null || header.value() == null
              ? null
              : new String(header.value(), StandardCharsets.UTF_8);
        }
      };

  private final AuditEventCodec codec;
  private final AuditEventProcessor processor;
  private final AuditTransportMetrics metrics;
  private final Tracer tracer;

  public KafkaAuditEventConsumer(
      final AuditEventCodec codec,
      final AuditEventProcessor processor,
      final AuditTransportMetrics metrics) {
    this(codec, processor, metrics, OpenTelemetry.noop());
  }

  public KafkaAuditEventConsumer(
      final AuditEventCodec codec,
      final AuditEventProcessor processor,
      final AuditTransportMetrics metrics,
      final OpenTelemetry openTelemetry) {
    this.codec = Objects.requireNonNull(codec, "codec");
    this.processor = Objects.requireNonNull(processor, "processor");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.tracer =
        Objects.requireNonNull(openTelemetry, "openTelemetry")
            .getTracer("com.medassist.auditgovernance.kafka");
  }

  @KafkaListener(
      topics = "${medassist.audit.transport.topic:audit-events}",
      groupId = "${medassist.audit.transport.consumer-group:audit-governance-v1}",
      containerFactory = "auditKafkaListenerContainerFactory",
      concurrency = "1")
  public void consume(
      final ConsumerRecord<String, byte[]> record,
      final Acknowledgment acknowledgment,
      final Consumer<?, ?> consumer) {
    Objects.requireNonNull(record, "record");
    Objects.requireNonNull(acknowledgment, "acknowledgment");
    final Context parent = W3C_PROPAGATOR.extract(Context.root(), record.headers(), HEADER_GETTER);
    final Span span =
        tracer
            .spanBuilder("audit-events.consume")
            .setParent(parent)
            .setSpanKind(SpanKind.CONSUMER)
            .startSpan();
    span.setAttribute(AttributeKey.stringKey("messaging.system"), "kafka");
    span.setAttribute(AttributeKey.stringKey("messaging.destination.name"), record.topic());
    span.setAttribute(AttributeKey.stringKey("messaging.operation.name"), "process");
    span.setAttribute(
        AttributeKey.longKey("messaging.destination.partition.id"), record.partition());
    final Context consumerContext = parent.with(span);
    try (Scope ignored = consumerContext.makeCurrent()) {
      if (record.partition() != 0) {
        throw new InvalidAuditEventException("audit-events must use partition zero only");
      }
      final AuditEvent event = codec.decode(record.value());
      if (!event.eventId().toString().equals(record.key())) {
        throw new InvalidAuditEventException("audit event key must equal event_id");
      }

      processor.process(event);
      updateLag(record, consumer);
      acknowledgment.acknowledge();
    } catch (final RuntimeException | Error failure) {
      span.recordException(failure);
      span.setStatus(StatusCode.ERROR);
      throw failure;
    } finally {
      span.end();
    }
  }

  private void updateLag(
      final ConsumerRecord<String, byte[]> record, final Consumer<?, ?> consumer) {
    if (consumer == null) {
      return;
    }
    final TopicPartition partition = new TopicPartition(record.topic(), record.partition());
    final Map<TopicPartition, Long> endOffsets = consumer.endOffsets(Set.of(partition));
    final Long endOffset = endOffsets.get(partition);
    if (endOffset != null) {
      metrics.setConsumerLag(endOffset - consumer.position(partition));
    }
  }
}
