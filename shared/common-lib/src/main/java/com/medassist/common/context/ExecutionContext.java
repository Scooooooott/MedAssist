package com.medassist.common.context;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable security and tracing context propagated across asynchronous work. */
public record ExecutionContext(
    String subject,
    Set<String> roles,
    String requestId,
    String traceId,
    Map<String, String> obligations) {

  public ExecutionContext {
    subject = requireText(subject, "subject");
    roles = immutableTextSet(roles, "roles");
    requestId = requireText(requestId, "requestId");
    traceId = requireText(traceId, "traceId");
    obligations = immutableMap(obligations, "obligations");
  }

  private static String requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static Set<String> immutableTextSet(final Set<String> values, final String name) {
    Objects.requireNonNull(values, name);
    if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(name + " must not contain blank values");
    }
    return Set.copyOf(values);
  }

  private static Map<String, String> immutableMap(
      final Map<String, String> values, final String name) {
    Objects.requireNonNull(values, name);
    if (values.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getValue().isBlank())) {
      throw new IllegalArgumentException(name + " must not contain blank keys or values");
    }
    return Map.copyOf(values);
  }
}
