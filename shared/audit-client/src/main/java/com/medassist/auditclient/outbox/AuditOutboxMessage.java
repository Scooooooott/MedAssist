package com.medassist.auditclient.outbox;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Durable protobuf value plus the safe W3C parent context captured before publication. */
public record AuditOutboxMessage(
    UUID eventId, byte[] payload, Map<String, String> parentTraceHeaders) {
  private static final Set<String> ALLOWED_TRACE_HEADERS = Set.of("traceparent", "tracestate");
  private static final int MAX_TRACE_HEADER_LENGTH = 512;

  public AuditOutboxMessage {
    Objects.requireNonNull(eventId, "eventId");
    payload = Objects.requireNonNull(payload, "payload").clone();
    if (payload.length == 0) {
      throw new IllegalArgumentException("audit outbox payload is required");
    }
    Objects.requireNonNull(parentTraceHeaders, "parentTraceHeaders");
    final Map<String, String> traceHeaders = new TreeMap<>();
    parentTraceHeaders.forEach(
        (key, value) -> {
          if (!ALLOWED_TRACE_HEADERS.contains(key)) {
            throw new IllegalArgumentException("unsupported audit trace header: " + key);
          }
          if (value == null || value.isBlank() || value.length() > MAX_TRACE_HEADER_LENGTH) {
            throw new IllegalArgumentException("audit trace header is invalid: " + key);
          }
          traceHeaders.put(key, value);
        });
    parentTraceHeaders = Collections.unmodifiableMap(traceHeaders);
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }

  public int payloadSizeBytes() {
    return payload.length;
  }

  @Override
  public boolean equals(final Object candidate) {
    return candidate instanceof AuditOutboxMessage other
        && eventId.equals(other.eventId)
        && Arrays.equals(payload, other.payload)
        && parentTraceHeaders.equals(other.parentTraceHeaders);
  }

  @Override
  public int hashCode() {
    int result = eventId.hashCode();
    result = 31 * result + Arrays.hashCode(payload);
    return 31 * result + parentTraceHeaders.hashCode();
  }
}
