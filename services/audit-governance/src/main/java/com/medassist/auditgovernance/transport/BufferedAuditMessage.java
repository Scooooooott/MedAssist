package com.medassist.auditgovernance.transport;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record BufferedAuditMessage(
    String eventId, byte[] payload, Map<String, String> parentTraceHeaders) {
  public BufferedAuditMessage(final String eventId, final byte[] payload) {
    this(eventId, payload, Map.of());
  }

  public BufferedAuditMessage {
    if (Objects.requireNonNull(eventId, "eventId").isBlank()) {
      throw new IllegalArgumentException("eventId is required");
    }
    payload = Objects.requireNonNull(payload, "payload").clone();
    if (payload.length == 0) {
      throw new IllegalArgumentException("payload is required");
    }
    parentTraceHeaders =
        Map.copyOf(new TreeMap<>(Objects.requireNonNull(parentTraceHeaders, "parentTraceHeaders")));
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }

  public int sizeBytes() {
    return payload.length;
  }

  @Override
  public boolean equals(final Object candidate) {
    return candidate instanceof BufferedAuditMessage other
        && eventId.equals(other.eventId)
        && Arrays.equals(payload, other.payload)
        && parentTraceHeaders.equals(other.parentTraceHeaders);
  }

  @Override
  public int hashCode() {
    return 31 * (31 * eventId.hashCode() + Arrays.hashCode(payload))
        + parentTraceHeaders.hashCode();
  }
}
