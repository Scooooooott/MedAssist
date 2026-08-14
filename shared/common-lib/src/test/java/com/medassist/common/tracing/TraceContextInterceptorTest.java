package com.medassist.common.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceContextInterceptorTest {
  private final RecordingExporter exporter = new RecordingExporter();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
  private final OpenTelemetry telemetry =
      OpenTelemetrySdk.builder()
          .setTracerProvider(provider)
          .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
          .build();

  @AfterEach
  void closeProvider() {
    provider.close();
  }

  @Test
  void clientCreatesChildSpanInjectsW3cContextAndScopesCallbacks() {
    final Metadata observedHeaders = new Metadata();
    final AtomicReference<String> callbackSpanId = new AtomicReference<>();
    final Channel channel =
        new Channel() {
          @Override
          public String authority() {
            return "test";
          }

          @Override
          public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
              final MethodDescriptor<RequestT, ResponseT> method, final CallOptions callOptions) {
            return new ClientCall<>() {
              @Override
              public void start(final Listener<ResponseT> listener, final Metadata headers) {
                observedHeaders.merge(headers);
                listener.onClose(Status.OK, new Metadata());
              }

              @Override
              public void request(final int count) {}

              @Override
              public void cancel(final String message, final Throwable cause) {}

              @Override
              public void halfClose() {}

              @Override
              public void sendMessage(final RequestT message) {}
            };
          }
        };
    final Span parent = telemetry.getTracer("test").spanBuilder("parent").startSpan();
    try (Scope ignored = parent.makeCurrent()) {
      final ClientCall<byte[], byte[]> call =
          new TraceContextClientInterceptor(telemetry)
              .interceptCall(method(), CallOptions.DEFAULT, channel);
      call.start(
          new ClientCall.Listener<>() {
            @Override
            public void onClose(final Status status, final Metadata trailers) {
              callbackSpanId.set(Span.current().getSpanContext().getSpanId());
            }
          },
          new Metadata());
    } finally {
      parent.end();
    }

    final SpanData client = exporter.named("grpc.client.test.Service/Call");
    final String traceparent = observedHeaders.get(GrpcTraceContext.TRACEPARENT_HEADER);
    assertNotNull(traceparent);
    assertTrue(traceparent.contains(client.getSpanId()));
    assertEquals(parent.getSpanContext().getTraceId(), client.getTraceId());
    assertEquals(parent.getSpanContext().getSpanId(), client.getParentSpanId());
    assertEquals(client.getSpanId(), callbackSpanId.get());
  }

  @Test
  void serverExtractsParentAndScopesHandler() {
    final Span parent = telemetry.getTracer("test").spanBuilder("parent").startSpan();
    final Metadata headers = new Metadata();
    telemetry
        .getPropagators()
        .getTextMapPropagator()
        .inject(
            Context.root().with(parent),
            headers,
            (carrier, key, value) ->
                carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value));
    final AtomicReference<String> handlerSpanId = new AtomicReference<>();
    final AtomicReference<String> propagatedTraceparent = new AtomicReference<>();

    final ServerCall.Listener<byte[]> listener =
        new TraceContextServerInterceptor(telemetry)
            .interceptCall(
                new TestServerCall(),
                headers,
                (call, ignoredHeaders) -> {
                  handlerSpanId.set(Span.current().getSpanContext().getSpanId());
                  propagatedTraceparent.set(GrpcTraceContext.currentTraceparent().orElseThrow());
                  return new ServerCall.Listener<>() {};
                });
    listener.onComplete();
    parent.end();

    final SpanData server = exporter.named("grpc.server.test.Service/Call");
    assertEquals(parent.getSpanContext().getTraceId(), server.getTraceId());
    assertEquals(parent.getSpanContext().getSpanId(), server.getParentSpanId());
    assertEquals(server.getSpanId(), handlerSpanId.get());
    assertTrue(propagatedTraceparent.get().contains(server.getSpanId()));
  }

  private static MethodDescriptor<byte[], byte[]> method() {
    return MethodDescriptor.<byte[], byte[]>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test.Service/Call")
        .setRequestMarshaller(new BytesMarshaller())
        .setResponseMarshaller(new BytesMarshaller())
        .build();
  }

  private static final class BytesMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    @Override
    public InputStream stream(final byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(final InputStream stream) {
      try {
        return stream.readAllBytes();
      } catch (java.io.IOException failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  private static final class TestServerCall extends ServerCall<byte[], byte[]> {
    @Override
    public void request(final int count) {}

    @Override
    public void sendHeaders(final Metadata headers) {}

    @Override
    public void sendMessage(final byte[] message) {}

    @Override
    public void close(final Status status, final Metadata trailers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
      return method();
    }

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }
  }

  private static final class RecordingExporter implements SpanExporter {
    private final List<SpanData> spans = new ArrayList<>();

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
