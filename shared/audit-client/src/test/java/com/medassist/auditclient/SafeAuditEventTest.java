package com.medassist.auditclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeAuditEventTest {
  @AfterEach
  void clearContext() {
    ContextCarrier.clear();
  }

  @Test
  void currentAuthenticatedContextOwnsIdentityAndCorrelationMetadata() {
    ContextCarrier.restore(
        new ExecutionContext(
            "clinician-7",
            Set.of("CLINICIAN"),
            "request-7",
            "0123456789abcdef0123456789abcdef",
            Map.of()));

    final SafeAuditEvent event =
        SafeAuditEvent.fromCurrentContext(
            SafeAuditCategory.DATA_ACCESS,
            "READ",
            "CLINICAL_DATA",
            "resource-7",
            "ALLOWED",
            Map.of("entity_count", "3"));

    assertThat(event.actor()).isEqualTo("clinician-7");
    assertThat(event.role()).isEqualTo("CLINICIAN");
    assertThat(event.eventId()).isNotNull();
    assertThat(event.occurredAt()).isNotNull();
    assertThat(event.safeMetadata())
        .containsEntry("request_id", "request-7")
        .containsEntry("trace_id", "0123456789abcdef0123456789abcdef")
        .containsEntry("entity_count", "3");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"query", "query_text", "text", "chunk", "prompt", "output", "model_output"})
  void sensitiveMetadataKeysAreRejected(final String key) {
    assertThatThrownBy(() -> eventWithMetadata(Map.of(key, "SENSITIVE_CANARY")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sensitive audit metadata key is forbidden");
  }

  @Test
  void unknownMetadataKeysAreRejected() {
    assertThatThrownBy(() -> eventWithMetadata(Map.of("custom_field", "value")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not whitelisted");
  }

  @Test
  void metadataEntryAndValueLimitsAreEnforced() {
    final Map<String, String> tooMany = new LinkedHashMap<>();
    for (int index = 0; index < SafeAuditMetadata.MAX_ENTRIES + 1; index++) {
      tooMany.put("key_" + index, "value");
    }

    assertThatThrownBy(() -> eventWithMetadata(tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("entry limit");
    assertThatThrownBy(
            () ->
                eventWithMetadata(
                    Map.of("reason_code", "x".repeat(SafeAuditMetadata.MAX_VALUE_LENGTH + 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value is unsafe");
  }

  @Test
  void callersCannotOverrideAuthenticatedCorrelationMetadata() {
    ContextCarrier.restore(
        new ExecutionContext("subject", Set.of("ADMIN"), "request", "trace", Map.of()));

    assertThatThrownBy(
            () ->
                SafeAuditEvent.fromCurrentContext(
                    SafeAuditCategory.SYSTEM,
                    "START",
                    "JOB",
                    "job-1",
                    "ALLOWED",
                    Map.of("trace_id", "spoofed")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be overridden");
  }

  private static SafeAuditEvent eventWithMetadata(final Map<String, String> metadata) {
    final SafeAuditEvent base = TestEvents.event();
    return new SafeAuditEvent(
        base.eventId(),
        base.occurredAt(),
        base.actor(),
        base.category(),
        base.role(),
        base.action(),
        base.resourceType(),
        base.resourceId(),
        base.outcome(),
        metadata);
  }
}
