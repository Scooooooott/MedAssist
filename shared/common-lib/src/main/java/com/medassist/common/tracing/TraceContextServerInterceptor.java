package com.medassist.common.tracing;

import com.medassist.common.security.TraceParent;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Extracts W3C context, creates a server span, and scopes all gRPC handler callbacks. */
public final class TraceContextServerInterceptor implements ServerInterceptor {
  private static final String INSTRUMENTATION_SCOPE = "com.medassist.common.grpc";
  private static final TextMapGetter<Metadata> METADATA_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(final Metadata carrier) {
          return carrier.keys();
        }

        @Override
        public String get(final Metadata carrier, final String key) {
          if (carrier == null || key == null || key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
            return null;
          }
          try {
            return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
          } catch (IllegalArgumentException ignored) {
            return null;
          }
        }
      };

  private final Tracer tracer;
  private final TextMapPropagator propagator;

  public TraceContextServerInterceptor() {
    this(GlobalOpenTelemetry.get());
  }

  public TraceContextServerInterceptor(final OpenTelemetry openTelemetry) {
    tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    propagator = openTelemetry.getPropagators().getTextMapPropagator();
  }

  @Override
  public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
      final ServerCall<RequestT, ResponseT> call,
      final Metadata headers,
      final ServerCallHandler<RequestT, ResponseT> next) {
    final Context parent = propagator.extract(Context.current(), headers, METADATA_GETTER);
    final Span span =
        tracer
            .spanBuilder("grpc.server." + call.getMethodDescriptor().getFullMethodName())
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan();
    SafeTelemetryAttributes.retainAllowed(
            Map.of(
                "rpc.service", serviceName(call),
                "rpc.method", call.getMethodDescriptor().getBareMethodName()))
        .forEach(span::setAttribute);
    final Context serverContext = parent.with(span);
    final AtomicBoolean ended = new AtomicBoolean();
    final String traceparent = traceparent(serverContext, span);
    final io.grpc.Context grpcContext =
        io.grpc.Context.current().withValue(GrpcTraceContext.CURRENT_TRACEPARENT, traceparent);

    final ServerCall<RequestT, ResponseT> tracedCall =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(final Status status, final Metadata trailers) {
            try (Scope ignored = serverContext.makeCurrent()) {
              super.close(status, trailers);
            } finally {
              endSpan(span, status, status.getCause(), ended);
            }
          }
        };

    try {
      return Contexts.interceptCall(
          grpcContext,
          tracedCall,
          headers,
          (contextCall, contextHeaders) -> {
            try (Scope ignored = serverContext.makeCurrent()) {
              return new ContextServerCallListener<>(
                  next.startCall(contextCall, contextHeaders), serverContext, span, ended);
            }
          });
    } catch (RuntimeException failure) {
      endSpan(span, Status.UNKNOWN, failure, ended);
      throw failure;
    }
  }

  private String traceparent(final Context context, final Span span) {
    final Metadata carrier = new Metadata();
    propagator.inject(
        context,
        carrier,
        (target, key, value) ->
            target.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value));
    final String propagated = carrier.get(GrpcTraceContext.TRACEPARENT_HEADER);
    return propagated == null
        ? TraceParent.createForTraceId(span.getSpanContext().getTraceId())
        : propagated;
  }

  private static String serviceName(final ServerCall<?, ?> call) {
    final String service =
        io.grpc.MethodDescriptor.extractFullServiceName(
            call.getMethodDescriptor().getFullMethodName());
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

  private static final class ContextServerCallListener<RequestT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<RequestT> {
    private final Context context;
    private final Span span;
    private final AtomicBoolean ended;

    private ContextServerCallListener(
        final ServerCall.Listener<RequestT> delegate,
        final Context context,
        final Span span,
        final AtomicBoolean ended) {
      super(delegate);
      this.context = context;
      this.span = span;
      this.ended = ended;
    }

    @Override
    public void onMessage(final RequestT message) {
      try (Scope ignored = context.makeCurrent()) {
        super.onMessage(message);
      }
    }

    @Override
    public void onHalfClose() {
      try (Scope ignored = context.makeCurrent()) {
        super.onHalfClose();
      }
    }

    @Override
    public void onCancel() {
      try (Scope ignored = context.makeCurrent()) {
        super.onCancel();
      } finally {
        endSpan(span, Status.CANCELLED, null, ended);
      }
    }

    @Override
    public void onComplete() {
      try (Scope ignored = context.makeCurrent()) {
        super.onComplete();
      } finally {
        endSpan(span, Status.OK, null, ended);
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
