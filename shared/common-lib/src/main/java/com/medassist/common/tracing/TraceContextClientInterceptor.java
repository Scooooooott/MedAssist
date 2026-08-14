package com.medassist.common.tracing;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates a client span and propagates its W3C context across Java-to-Python gRPC calls. */
public final class TraceContextClientInterceptor implements ClientInterceptor {
  private static final String INSTRUMENTATION_SCOPE = "com.medassist.common.grpc";

  private final Tracer tracer;
  private final TextMapPropagator propagator;

  public TraceContextClientInterceptor() {
    this(GlobalOpenTelemetry.get());
  }

  public TraceContextClientInterceptor(final OpenTelemetry openTelemetry) {
    tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    propagator = openTelemetry.getPropagators().getTextMapPropagator();
  }

  @Override
  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
      final MethodDescriptor<RequestT, ResponseT> method,
      final CallOptions callOptions,
      final Channel next) {
    final Context parent = Context.current();
    final Span span =
        tracer
            .spanBuilder("grpc.client." + method.getFullMethodName())
            .setParent(parent)
            .setSpanKind(SpanKind.CLIENT)
            .startSpan();
    SafeTelemetryAttributes.retainAllowed(
            Map.of(
                "rpc.service", serviceName(method),
                "rpc.method", method.getBareMethodName()))
        .forEach(span::setAttribute);
    final Context callContext = parent.with(span);
    final AtomicBoolean ended = new AtomicBoolean();

    final ClientCall<RequestT, ResponseT> delegate;
    try (Scope ignored = callContext.makeCurrent()) {
      delegate = next.newCall(method, callOptions);
    } catch (RuntimeException failure) {
      endSpan(span, Status.UNKNOWN, failure, ended);
      throw failure;
    }

    return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
      @Override
      public void start(final Listener<ResponseT> listener, final Metadata headers) {
        propagator.inject(
            callContext,
            headers,
            (carrier, key, value) -> {
              final Metadata.Key<String> metadataKey =
                  Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
              carrier.discardAll(metadataKey);
              carrier.put(metadataKey, value);
            });
        try (Scope ignored = callContext.makeCurrent()) {
          super.start(new ContextClientCallListener<>(listener, callContext, span, ended), headers);
        } catch (RuntimeException failure) {
          endSpan(span, Status.UNKNOWN, failure, ended);
          throw failure;
        }
      }

      @Override
      public void cancel(final String message, final Throwable cause) {
        endSpan(span, Status.CANCELLED, cause, ended);
        super.cancel(message, cause);
      }
    };
  }

  private static String serviceName(final MethodDescriptor<?, ?> method) {
    final String service = MethodDescriptor.extractFullServiceName(method.getFullMethodName());
    return service == null ? "unknown" : service;
  }

  private static void endSpan(
      final Span span, final Status status, final Throwable failure, final AtomicBoolean ended) {
    if (!ended.compareAndSet(false, true)) {
      return;
    }
    if (!status.isOk()) {
      span.setStatus(StatusCode.ERROR);
    }
    if (failure != null) {
      span.recordException(failure);
    }
    span.end();
  }

  private static final class ContextClientCallListener<ResponseT>
      extends ForwardingClientCallListener.SimpleForwardingClientCallListener<ResponseT> {
    private final Context context;
    private final Span span;
    private final AtomicBoolean ended;

    private ContextClientCallListener(
        final ClientCall.Listener<ResponseT> delegate,
        final Context context,
        final Span span,
        final AtomicBoolean ended) {
      super(delegate);
      this.context = context;
      this.span = span;
      this.ended = ended;
    }

    @Override
    public void onHeaders(final Metadata headers) {
      try (Scope ignored = context.makeCurrent()) {
        super.onHeaders(headers);
      }
    }

    @Override
    public void onMessage(final ResponseT message) {
      try (Scope ignored = context.makeCurrent()) {
        super.onMessage(message);
      }
    }

    @Override
    public void onClose(final Status status, final Metadata trailers) {
      try (Scope ignored = context.makeCurrent()) {
        super.onClose(status, trailers);
      } finally {
        endSpan(span, status, status.getCause(), ended);
      }
    }

    @Override
    public void onReady() {
      try (Scope ignored = context.makeCurrent()) {
        super.onReady();
      }
    }
  }
}
