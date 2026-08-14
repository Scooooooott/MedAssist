package com.medassist.auditgovernance.transport;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditEventPublisher;
import io.opentelemetry.api.GlobalOpenTelemetry;
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
import org.springframework.scheduling.annotation.Scheduled;

/** Kafka/Redpanda transport adapter; callers continue to depend on AuditEventPublisher. */
public final class KafkaAuditEventPublisher implements AuditEventPublisher {
  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);
  private static final String TRACEPARENT = "traceparent";
  private static final String TRACESTATE = "tracestate";
  private static final TextMapSetter<Headers> HEADER_SETTER =
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
  private final AuditEventCodec codec;
  private final AuditEventValidator validator;
  private final DurableAuditEventBuffer buffer;
  private final AuditTransportMetrics metrics;
  private final Duration sendTimeout;
  private final TextMapPropagator propagator;
  private final Tracer tracer;
  private final AtomicBoolean draining = new AtomicBoolean();

  public KafkaAuditEventPublisher(
      final KafkaTemplate<String, byte[]> kafkaTemplate,
      final String topic,
      final AuditEventCodec codec,
      final AuditEventValidator validator,
      final DurableAuditEventBuffer buffer,
      final AuditTransportMetrics metrics,
      final Duration sendTimeout) {
    this(
        kafkaTemplate,
        topic,
        codec,
        validator,
        buffer,
        metrics,
        sendTimeout,
        GlobalOpenTelemetry.get());
  }

  public KafkaAuditEventPublisher(
      final KafkaTemplate<String, byte[]> kafkaTemplate,
      final String topic,
      final AuditEventCodec codec,
      final AuditEventValidator validator,
      final DurableAuditEventBuffer buffer,
      final AuditTransportMetrics metrics,
      final Duration sendTimeout,
      final OpenTelemetry openTelemetry) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
    this.topic = Objects.requireNonNull(topic, "topic");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
    final OpenTelemetry telemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
    this.propagator = telemetry.getPropagators().getTextMapPropagator();
    this.tracer = telemetry.getTracer("com.medassist.auditgovernance.transport");
    metrics.setBufferDepth(buffer.size());
  }

  @Override
  public AuditEvent publish(final AuditEvent event) {
    validator.validateForTransport(event);
    final BufferedAuditMessage message =
        new BufferedAuditMessage(
            event.eventId().toString(), codec.encode(event), captureCurrentTraceHeaders());
    if (!bufferMessage(message)) {
      throw new AuditBufferFullException("audit event buffer is full", null);
    }
    startDrain();
    return event;
  }

  @Scheduled(fixedDelayString = "${medassist.audit.transport.buffer.drain-delay-ms:5000}")
  public void drainBufferedEvents() {
    startDrain();
  }

  private void startDrain() {
    if (!draining.compareAndSet(false, true)) {
      return;
    }
    sendHead();
  }

  private void sendHead() {
    final BufferedAuditMessage message = buffer.peek().orElse(null);
    if (message == null) {
      draining.set(false);
      if (buffer.peek().isPresent()) {
        startDrain();
      }
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
        new ProducerRecord<>(topic, message.eventId(), message.payload());
    final CompletableFuture<org.springframework.kafka.support.SendResult<String, byte[]>> future;
    try {
      try (Scope ignored = span.makeCurrent()) {
        propagator.inject(Context.current(), record.headers(), HEADER_SETTER);
        future = kafkaTemplate.send(record);
      }
    } catch (final RuntimeException exception) {
      span.recordException(exception);
      span.setStatus(StatusCode.ERROR);
      span.end();
      LOGGER.warn("Audit delivery failed; event remains in durable buffer");
      draining.set(false);
      return;
    }
    future
        .orTimeout(sendTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .whenComplete(
            (result, failure) -> {
              if (failure != null) {
                span.recordException(failure);
                span.setStatus(StatusCode.ERROR);
                span.end();
                LOGGER.warn("Audit delivery failed; event remains in durable buffer");
                draining.set(false);
                return;
              }
              try {
                buffer.acknowledge(message);
                metrics.setBufferDepth(buffer.size());
                span.setStatus(StatusCode.OK);
                span.end();
                sendHead();
              } catch (final RuntimeException exception) {
                span.recordException(exception);
                span.setStatus(StatusCode.ERROR);
                span.end();
                LOGGER.warn("Audit buffer acknowledgement failed; event remains durable");
                draining.set(false);
              }
            });
  }

  private boolean bufferMessage(final BufferedAuditMessage message) {
    final boolean accepted = buffer.offer(message);
    if (accepted) {
      metrics.recordBuffered(buffer.size());
    } else {
      metrics.recordBufferRejected(buffer.size());
    }
    return accepted;
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
