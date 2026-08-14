package com.medassist.auditclient;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

final class SafeAuditMetadata {
  static final int MAX_ENTRIES = 16;
  static final int MAX_VALUE_LENGTH = 256;
  static final int MAX_TOTAL_LENGTH = 2048;

  private static final Set<String> ALLOWED_KEYS =
      Set.of(
          "trace_id",
          "request_id",
          "policy_version",
          "reason_code",
          "entity_type",
          "entity_count",
          "result_code",
          "obligation",
          "content_domain",
          "duration_ms",
          "source_service",
          "decision_code",
          "model_version",
          "operation_id",
          "record_count",
          "status_code");
  private static final Set<String> SENSITIVE_KEY_MARKERS =
      Set.of("query", "text", "chunk", "prompt", "output");

  private SafeAuditMetadata() {}

  static Map<String, String> sanitize(final Map<String, String> metadata) {
    Objects.requireNonNull(metadata, "metadata");
    if (metadata.size() > MAX_ENTRIES) {
      throw new IllegalArgumentException("audit metadata exceeds entry limit");
    }
    final Map<String, String> sorted = new TreeMap<>();
    final int[] totalLength = {0};
    metadata.forEach(
        (key, value) -> {
          final String requiredKey = requireKey(key);
          final String requiredValue = requireValue(requiredKey, value);
          totalLength[0] += requiredKey.length() + requiredValue.length();
          if (totalLength[0] > MAX_TOTAL_LENGTH) {
            throw new IllegalArgumentException("audit metadata exceeds total length limit");
          }
          sorted.put(requiredKey, requiredValue);
        });
    return Collections.unmodifiableMap(sorted);
  }

  private static String requireKey(final String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("audit metadata key is required");
    }
    final String normalized = key.toLowerCase(java.util.Locale.ROOT);
    if (SENSITIVE_KEY_MARKERS.stream().anyMatch(normalized::contains)) {
      throw new IllegalArgumentException("sensitive audit metadata key is forbidden: " + key);
    }
    if (!ALLOWED_KEYS.contains(key)) {
      throw new IllegalArgumentException("audit metadata key is not whitelisted: " + key);
    }
    return key;
  }

  private static String requireValue(final String key, final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("audit metadata value is required: " + key);
    }
    if (value.length() > MAX_VALUE_LENGTH
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("audit metadata value is unsafe: " + key);
    }
    return value;
  }
}
