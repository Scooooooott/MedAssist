package com.medassist.common;

import java.util.Objects;
import java.util.UUID;

public record RequestIds(String traceId, String requestId) {
  public RequestIds {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(requestId, "requestId");
  }

  public static RequestIds create() {
    return new RequestIds(UUID.randomUUID().toString(), UUID.randomUUID().toString());
  }
}
