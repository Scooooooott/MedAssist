package com.medassist.common.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Objects;

/** Records one safe degradation projection across metrics, tracing, and audit. */
public final class ObservedDegradationRecorder implements DegradationRecorder {
  private final MeterRegistry meterRegistry;
  private final Tracer tracer;
  private final DegradationAuditSink auditSink;
  private final DegradationTrajectorySink trajectorySink;

  public ObservedDegradationRecorder(
      final MeterRegistry meterRegistry,
      final Tracer tracer,
      final DegradationAuditSink auditSink) {
    this(meterRegistry, tracer, auditSink, null);
  }

  public ObservedDegradationRecorder(
      final MeterRegistry meterRegistry,
      final Tracer tracer,
      final DegradationAuditSink auditSink,
      final DegradationTrajectorySink trajectorySink) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.tracer = tracer;
    this.auditSink = auditSink;
    this.trajectorySink = trajectorySink;
  }

  @Override
  public void record(final DegradationEvent event) {
    Objects.requireNonNull(event, "event");
    Counter.builder("medassist.degradation.events")
        .tag("component", event.component().name())
        .tag("code", event.code())
        .tag("stage", event.affectedStage())
        .tag("fallback_mode", event.fallbackMode().name())
        .register(meterRegistry)
        .increment();

    final Span span = tracer == null ? null : tracer.currentSpan();
    if (span != null) {
      span.tag("degradation.code", event.code());
      span.tag("degradation.stage", event.affectedStage());
      span.tag("degradation.fallback_mode", event.fallbackMode().name());
      span.event("medassist.degradation");
    }
    if (auditSink != null) {
      auditSink.publish(event);
    }
    if (trajectorySink != null) {
      trajectorySink.publish(event);
    }
  }
}
