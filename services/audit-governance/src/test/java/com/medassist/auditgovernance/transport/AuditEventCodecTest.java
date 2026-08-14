package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditPayload;
import com.medassist.contracts.v1.AuditEventEnvelope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventCodecTest {
  private final AuditEventCodec codec = new AuditEventCodec(new AuditEventValidator());

  @Test
  void protobufBytesRoundTripMetadataOnlyEvent() throws Exception {
    final AuditEvent source = event();

    final byte[] encoded = codec.encode(source);
    final AuditEventEnvelope envelope = AuditEventEnvelope.parseFrom(encoded);

    assertThat(envelope.getSchemaVersion()).isEqualTo(AuditEventCodec.SCHEMA_VERSION);
    assertThat(envelope.getEventId()).isEqualTo(source.eventId().toString());
    assertThat(envelope.getSafeMetadataMap()).containsExactlyEntriesOf(source.payload().fields());
    assertThat(envelope.getSafeMetadataSha256()).isEqualTo(source.payloadHash());
    assertThat(codec.decode(encoded)).isEqualTo(source);
  }

  @Test
  void malformedProtobufBytesAreRejected() {
    assertThatThrownBy(() -> codec.decode(new byte[] {(byte) 0x80}))
        .isInstanceOf(InvalidAuditEventException.class)
        .hasMessage("audit event payload is invalid");
  }

  @Test
  void unapprovedSensitiveMetadataIsRejectedOnDecode() throws Exception {
    final AuditEventEnvelope envelope = AuditEventEnvelope.parseFrom(codec.encode(event()));
    final byte[] malicious =
        envelope.toBuilder()
            .putSafeMetadata("query", "SENSITIVE_QUERY_CANARY")
            .build()
            .toByteArray();

    assertThatThrownBy(() -> codec.decode(malicious))
        .isInstanceOf(InvalidAuditEventException.class)
        .hasMessage("audit event payload is invalid");
  }

  @Test
  void chainFieldsAreNeitherDefinedNorAcceptedForEncoding() {
    assertThat(AuditEventEnvelope.getDescriptor().getFields())
        .extracting(field -> field.getName())
        .doesNotContain("previous_hash", "hash", "chain_hash", "query", "text", "payload");
    final AuditEvent chained = event().withPreviousHash("previous-chain-hash").withHash("hash");

    assertThatThrownBy(() -> codec.encode(chained))
        .isInstanceOf(InvalidAuditEventException.class)
        .hasMessage("transport event must not provide hash-chain fields");
  }

  @Test
  void tamperedSafeMetadataHashIsRejected() throws Exception {
    final AuditEventEnvelope envelope = AuditEventEnvelope.parseFrom(codec.encode(event()));
    final byte[] tampered =
        envelope.toBuilder().setSafeMetadataSha256("0".repeat(64)).build().toByteArray();

    assertThatThrownBy(() -> codec.decode(tampered))
        .isInstanceOf(InvalidAuditEventException.class)
        .hasMessage("audit payload hash does not match safe metadata");
  }

  private static AuditEvent event() {
    return new AuditEvent(
        UUID.fromString("3f741f2b-f5a6-4fd6-8b8b-aa21d418a795"),
        Instant.parse("2026-01-01T00:00:00.123456789Z"),
        "service-a",
        "CLINICIAN",
        "READ",
        "CLINICAL_DATA",
        "resource-1",
        "ALLOWED",
        AuditPayload.of(Map.of("entityCount", "2", "policyVersion", "policy-v1")));
  }
}
