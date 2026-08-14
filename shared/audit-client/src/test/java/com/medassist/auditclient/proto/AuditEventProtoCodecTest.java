package com.medassist.auditclient.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.auditclient.SafeAuditCategory;
import com.medassist.auditclient.SafeAuditEvent;
import com.medassist.auditclient.TestEvents;
import com.medassist.contracts.v1.AuditEventEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEventProtoCodecTest {
  private final AuditEventProtoCodec codec = new AuditEventProtoCodec();

  @Test
  void protobufRoundTripIncludesDeterministicMetadataHash() throws Exception {
    final SafeAuditEvent source = TestEvents.event();

    final byte[] encoded = codec.encode(source);
    final AuditEventEnvelope envelope = AuditEventEnvelope.parseFrom(encoded);

    assertThat(envelope.getSchemaVersion()).isEqualTo(AuditEventProtoCodec.SCHEMA_VERSION);
    assertThat(envelope.getEventId()).isEqualTo(source.eventId().toString());
    assertThat(envelope.getSafeMetadataMap()).containsExactlyEntriesOf(source.safeMetadata());
    assertThat(envelope.getSafeMetadataSha256()).hasSize(64);
    assertThat(codec.decode(encoded)).isEqualTo(source);
  }

  @Test
  void metadataHashDoesNotDependOnMapInsertionOrder() throws Exception {
    final Map<String, String> first = new LinkedHashMap<>();
    first.put("policy_version", "v1");
    first.put("entity_count", "2");
    final Map<String, String> second = new LinkedHashMap<>();
    second.put("entity_count", "2");
    second.put("policy_version", "v1");

    final String firstHash =
        AuditEventEnvelope.parseFrom(codec.encode(withMetadata(first))).getSafeMetadataSha256();
    final String secondHash =
        AuditEventEnvelope.parseFrom(codec.encode(withMetadata(second))).getSafeMetadataSha256();

    assertThat(firstHash).isEqualTo(secondHash);
  }

  @Test
  void everySafeCategoryMapsBothDirections() {
    for (final SafeAuditCategory category : SafeAuditCategory.values()) {
      final SafeAuditEvent source = withCategory(category);
      assertThat(codec.decode(codec.encode(source)).category()).isEqualTo(category);
    }
  }

  private static SafeAuditEvent withMetadata(final Map<String, String> metadata) {
    final SafeAuditEvent source = TestEvents.event();
    return new SafeAuditEvent(
        source.eventId(),
        source.occurredAt(),
        source.actor(),
        source.category(),
        source.role(),
        source.action(),
        source.resourceType(),
        source.resourceId(),
        source.outcome(),
        metadata);
  }

  private static SafeAuditEvent withCategory(final SafeAuditCategory category) {
    final SafeAuditEvent source = TestEvents.event();
    return new SafeAuditEvent(
        source.eventId(),
        source.occurredAt(),
        source.actor(),
        category,
        source.role(),
        source.action(),
        source.resourceType(),
        source.resourceId(),
        source.outcome(),
        source.safeMetadata());
  }
}
