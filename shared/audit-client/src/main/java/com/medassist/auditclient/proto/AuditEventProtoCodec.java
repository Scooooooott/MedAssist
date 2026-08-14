package com.medassist.auditclient.proto;

import com.google.protobuf.InvalidProtocolBufferException;
import com.medassist.auditclient.SafeAuditCategory;
import com.medassist.auditclient.SafeAuditEvent;
import com.medassist.contracts.v1.AuditCategory;
import com.medassist.contracts.v1.AuditEventEnvelope;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Encodes the public safe event model into the shared protobuf audit contract. */
public final class AuditEventProtoCodec {
  public static final int SCHEMA_VERSION = 1;

  public byte[] encode(final SafeAuditEvent event) {
    return AuditEventEnvelope.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setEventId(event.eventId().toString())
        .setOccurredAtEpochSeconds(event.occurredAt().getEpochSecond())
        .setOccurredAtNanos(event.occurredAt().getNano())
        .setActor(event.actor())
        .setCategory(toProto(event.category()))
        .setRole(event.role())
        .setAction(event.action())
        .setResourceType(event.resourceType())
        .setResourceId(event.resourceId())
        .setOutcome(event.outcome())
        .putAllSafeMetadata(event.safeMetadata())
        .setSafeMetadataSha256(hashMetadata(event.safeMetadata()))
        .build()
        .toByteArray();
  }

  public SafeAuditEvent decode(final byte[] payload) {
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("audit protobuf payload is required");
    }
    try {
      final AuditEventEnvelope envelope = AuditEventEnvelope.parseFrom(payload);
      if (envelope.getSchemaVersion() != SCHEMA_VERSION) {
        throw new IllegalArgumentException("unsupported audit protobuf schema version");
      }
      final String expectedHash = hashMetadata(envelope.getSafeMetadataMap());
      if (!expectedHash.equals(envelope.getSafeMetadataSha256())) {
        throw new IllegalArgumentException("audit metadata hash does not match payload");
      }
      return new SafeAuditEvent(
          UUID.fromString(envelope.getEventId()),
          Instant.ofEpochSecond(
              envelope.getOccurredAtEpochSeconds(), envelope.getOccurredAtNanos()),
          envelope.getActor(),
          fromProto(envelope.getCategory()),
          envelope.getRole(),
          envelope.getAction(),
          envelope.getResourceType(),
          envelope.getResourceId(),
          envelope.getOutcome(),
          envelope.getSafeMetadataMap());
    } catch (final InvalidProtocolBufferException exception) {
      throw new IllegalArgumentException("audit protobuf payload is invalid", exception);
    } catch (final RuntimeException exception) {
      if (exception instanceof IllegalArgumentException) {
        throw exception;
      }
      throw new IllegalArgumentException("audit protobuf payload is invalid", exception);
    }
  }

  static String hashMetadata(final Map<String, String> metadata) {
    final MessageDigest digest = sha256();
    new TreeMap<>(metadata)
        .forEach(
            (key, value) -> {
              updateLengthDelimited(digest, key);
              updateLengthDelimited(digest, value);
            });
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void updateLengthDelimited(final MessageDigest digest, final String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }

  private static AuditCategory toProto(final SafeAuditCategory category) {
    return switch (category) {
      case AUTHENTICATION -> AuditCategory.AUDIT_CATEGORY_AUTHENTICATION;
      case AUTHORIZATION -> AuditCategory.AUDIT_CATEGORY_AUTHORIZATION;
      case DATA_ACCESS -> AuditCategory.AUDIT_CATEGORY_DATA_ACCESS;
      case PHI_SAFETY -> AuditCategory.AUDIT_CATEGORY_PHI_SAFETY;
      case GOVERNANCE -> AuditCategory.AUDIT_CATEGORY_GOVERNANCE;
      case SYSTEM -> AuditCategory.AUDIT_CATEGORY_SYSTEM;
    };
  }

  private static SafeAuditCategory fromProto(final AuditCategory category) {
    return switch (category) {
      case AUDIT_CATEGORY_AUTHENTICATION -> SafeAuditCategory.AUTHENTICATION;
      case AUDIT_CATEGORY_AUTHORIZATION -> SafeAuditCategory.AUTHORIZATION;
      case AUDIT_CATEGORY_DATA_ACCESS -> SafeAuditCategory.DATA_ACCESS;
      case AUDIT_CATEGORY_PHI_SAFETY -> SafeAuditCategory.PHI_SAFETY;
      case AUDIT_CATEGORY_GOVERNANCE -> SafeAuditCategory.GOVERNANCE;
      case AUDIT_CATEGORY_SYSTEM -> SafeAuditCategory.SYSTEM;
      case AUDIT_CATEGORY_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("audit category is required");
    };
  }
}
