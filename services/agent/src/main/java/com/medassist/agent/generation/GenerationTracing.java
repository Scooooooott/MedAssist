package com.medassist.agent.generation;

import com.medassist.common.tracing.SafeTelemetryAttributes;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.function.Supplier;

/** Creates body-free generation spans with centrally allowlisted low-cardinality attributes. */
public final class GenerationTracing {
  private final Tracer tracer;

  public GenerationTracing(final Tracer tracer) {
    this.tracer = tracer;
  }

  public Span currentSpan() {
    return tracer == null ? null : tracer.currentSpan();
  }

  public <T> T trace(
      final String name,
      final Span explicitParent,
      final Map<String, ?> attributes,
      final Supplier<T> operation) {
    if (tracer == null) {
      return operation.get();
    }
    final Span parent = explicitParent == null ? tracer.currentSpan() : explicitParent;
    final Span span = (parent == null ? tracer.nextSpan() : tracer.nextSpan(parent)).name(name);
    SafeTelemetryAttributes.retainAllowed(attributes).forEach(span::tag);
    span.start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return operation.get();
    } catch (final RuntimeException | Error exception) {
      span.error(exception);
      throw exception;
    } finally {
      span.end();
    }
  }

  public <T> T trace(
      final String name, final Map<String, ?> attributes, final Supplier<T> operation) {
    return trace(name, null, attributes, operation);
  }
}
