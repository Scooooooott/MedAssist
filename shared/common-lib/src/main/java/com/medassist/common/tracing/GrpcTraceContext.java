package com.medassist.common.tracing;

import io.grpc.Context;
import io.grpc.Metadata;
import java.util.Optional;

public final class GrpcTraceContext {
  static final Metadata.Key<String> TRACEPARENT_HEADER =
      Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  static final Context.Key<String> CURRENT_TRACEPARENT = Context.key("medassist-traceparent");

  private GrpcTraceContext() {}

  public static Optional<String> currentTraceparent() {
    return Optional.ofNullable(CURRENT_TRACEPARENT.get());
  }
}
