package com.medassist.agent.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationTracingTest {
  @Test
  void createsChildSpanWithOnlyAllowlistedBodyFreeAttributes() {
    final Tracer tracer = mock(Tracer.class);
    final Span parent = mock(Span.class);
    final Span child = mock(Span.class);
    final Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
    when(tracer.currentSpan()).thenReturn(parent);
    when(tracer.nextSpan(parent)).thenReturn(child);
    when(child.name("medassist.generation.create")).thenReturn(child);
    when(child.start()).thenReturn(child);
    when(tracer.withSpan(child)).thenReturn(scope);

    final String result =
        new GenerationTracing(tracer)
            .trace(
                "medassist.generation.create",
                Map.of(
                    "generation.operation",
                    "create",
                    "query",
                    "private body",
                    "event.payload",
                    "private event"),
                () -> "ok");

    assertEquals("ok", result);
    verify(child).tag("generation.operation", "create");
    verify(child, never()).tag(eq("query"), anyString());
    verify(child, never()).tag(eq("event.payload"), anyString());
    verify(child).end();
  }
}
