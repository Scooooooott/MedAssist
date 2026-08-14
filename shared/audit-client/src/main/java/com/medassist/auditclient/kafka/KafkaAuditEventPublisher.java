package com.medassist.auditclient.kafka;

import com.medassist.auditclient.AuditEventPublisher;
import com.medassist.auditclient.SafeAuditEvent;
import com.medassist.auditclient.outbox.AuditOutbox;
import com.medassist.auditclient.outbox.AuditOutboxFullException;
import com.medassist.auditclient.outbox.AuditOutboxMessage;
import com.medassist.auditclient.proto.AuditEventProtoCodec;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;

/** Durable outbox publisher that emits protobuf bytes and W3C-correlated producer spans. */
public final class KafkaAuditEventPublisher implements AuditEventPublisher {
  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);
  private static final String TRACEPARENT = "traceparent";
  private static final String TRACESTATE = "tracestate";
  private static final TextMapSetter<Headers> KAFKA_HEADER_SETTER =
      (headers, key, value) -> {
        if (headers != null && isTraceHeader(key)) {
          headers.remove(key);
          headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
      };
  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(final Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(final Map<String, String> carrier, final String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final String topic;
  private final AuditEventProtoCodec codec;
  private final AuditOutbox outbox;
  private final AuditClientMetrics metrics;
  private final TextMapPropagator propagator;
  private final Tracer tracer;
  private final Duration sendTimeout;
  private final AtomicBoolean draining = new AtomicBoolean();

  public KafkaAuditEventPublisher(
      final KafkaTemplate<String, byte[]> kafkaTemplate,
      final String topic,
      final AuditEventProtoCodec codec,
      final AuditOutbox outbox,
      final AuditClientMetrics metrics,
      final OpenTelemetry openTelemetry,
      final TextMapPropagator propagator,
      final Duration sendTimeout) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("audit topic is required");
    }
    this.topic = topic;
    this.codec = Objects.requireNonNull(codec, "codec");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.propagator = Objects.requireNonNull(propagator, "propagator");
    this.tracer =
        Objects.requireNonNull(openTelemetry, "openTelemetry")
            .getTracer("com.medassist.auditclient");
    this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
    if (sendTimeout.isZero() || sendTimeout.isNegative()) {
      throw new IllegalArgumentException("audit send timeout must be positive");
    }
    metrics.setDepth(outbox.size());
  }

  @Override
  public void publish(final SafeAuditEvent event) {
    final AuditOutboxMessage message =
        new AuditOutboxMessage(event.eventId(), codec.encode(event), captureCurrentTraceHeaders());
    try {
      outbox.append(message);
      metrics.recordPersisted(outbox.size());
    } catch (final AuditOutboxFullException exception) {
      metrics.recordRejected(outbox.size());
      throw exception;
    }
    startDrain();
  }

  @Scheduled(fixedDelayString = "${medassist.audit.client.drain-delay:5s}")
  public void drain() {
    startDrain();
  }

  private void startDrain() {
    if (draining.compareAndSet(false, true)) {
      sendHead();
    }
  }

  private void sendHead() {
    final AuditOutboxMessage message = outbox.peek().orElse(null);
    if (message == null) {
      releaseDrainAndCheckRace();
      return;
    }

    final Context parent =
        propagator.extract(Context.root(), message.parentTraceHeaders(), MAP_GETTER);
    final Span span =
        tracer
            .spanBuilder("messaging.publish.audit-events")
            .setParent(parent)
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination.name", topic)
            .setAttribute("messaging.operation.name", "publish")
            .startSpan();
    final ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(topic, message.eventId().toString(), message.payload());
    final CompletableFuture<SendResult<String, byte[]>> sendFuture;
    try (Scope ignored = span.makeCurrent()) {
      propagator.inject(Context.current(), record.headers(), KAFKA_HEADER_SETTER);
      sendFuture = kafkaTemplate.send(record);
    } catch (final RuntimeException exception) {
      completeFailure(message, span, exception);
      return;
    }

    sendFuture
        .orTimeout(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .whenComplete(
            (result, failure) -> {
              if (failure != null) {
                completeFailure(message, span, failure);
                return;
              }
              try {
                outbox.acknowledge(message);
                metrics.recordDelivered(outbox.size());
              } catch (final RuntimeException exception) {
                completeFailure(message, span, exception);
                return;
              }
              span.setStatus(StatusCode.OK);
              span.end();
              sendHead();
            });
  }

  private void completeFailure(
      final AuditOutboxMessage message, final Span span, final Throwable failure) {
    span.recordException(failure);
    span.setStatus(StatusCode.ERROR);
    span.end();
    metrics.recordDeliveryFailure(outbox.size());
    LOGGER.warn("Audit delivery failed; event_id={} remains in outbox", message.eventId());
    draining.set(false);
  }

  private void releaseDrainAndCheckRace() {
    draining.set(false);
    if (outbox.peek().isPresent()) {
      startDrain();
    }
  }

  private Map<String, String> captureCurrentTraceHeaders() {
    final Map<String, String> headers = new TreeMap<>();
    propagator.inject(
        Context.current(),
        headers,
        (carrier, key, value) -> {
          if (carrier != null && isTraceHeader(key)) {
            carrier.put(key, value);
          }
        });
    return headers;
  }

  private static boolean isTraceHeader(final String key) {
    return TRACEPARENT.equalsIgnoreCase(key) || TRACESTATE.equalsIgnoreCase(key);
  }
}
