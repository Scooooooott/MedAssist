package com.medassist.auditgovernance.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditEventCategory;
import com.medassist.auditgovernance.AuditPayload;
import com.medassist.contracts.v1.AuditCategory;
import com.medassist.contracts.v1.AuditEventEnvelope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Maps the metadata-only audit domain object to the generated protobuf wire contract. */
public final class AuditEventCodec {
  static final int SCHEMA_VERSION = 1;

  private final AuditEventValidator validator;

  public AuditEventCodec(final AuditEventValidator validator) {
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  public byte[] encode(final AuditEvent event) {
    if (event == null) {
      throw new InvalidAuditEventException("audit event is required");
    }
    validator.validateForTransport(event);
    return AuditEventEnvelope.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setEventId(event.eventId().toString())
        .setOccurredAtEpochSeconds(event.timestamp().getEpochSecond())
        .setOccurredAtNanos(event.timestamp().getNano())
        .setActor(event.actor())
        .setCategory(toContractCategory(event.category()))
        .setRole(event.role())
        .setAction(event.action())
        .setResourceType(event.resourceType())
        .setResourceId(event.resourceId())
        .setOutcome(event.outcome())
        .putAllSafeMetadata(event.payload().fields())
        .setSafeMetadataSha256(event.payloadHash())
        .build()
        .toByteArray();
  }

  public AuditEvent decode(final byte[] payload) {
    if (payload == null || payload.length == 0) {
      throw new InvalidAuditEventException("audit event payload is required");
    }
    try {
      final AuditEventEnvelope envelope = AuditEventEnvelope.parseFrom(payload);
      if (envelope.getSchemaVersion() != SCHEMA_VERSION) {
        throw new InvalidAuditEventException("unsupported audit event schema version");
      }
      if (envelope.getSafeMetadataSha256().isBlank()) {
        throw new InvalidAuditEventException("audit safe metadata hash is required");
      }
      final AuditEvent event =
          new AuditEvent(
              UUID.fromString(envelope.getEventId()),
              Instant.ofEpochSecond(
                  envelope.getOccurredAtEpochSeconds(), envelope.getOccurredAtNanos()),
              envelope.getActor(),
              fromContractCategory(envelope.getCategory()),
              envelope.getRole(),
              envelope.getAction(),
              envelope.getResourceType(),
              envelope.getResourceId(),
              envelope.getOutcome(),
              AuditPayload.of(envelope.getSafeMetadataMap()),
              envelope.getSafeMetadataSha256(),
              "",
              "");
      validator.validateForTransport(event);
      return event;
    } catch (final InvalidAuditEventException exception) {
      throw exception;
    } catch (final InvalidProtocolBufferException exception) {
      throw new InvalidAuditEventException("audit event payload is invalid", exception);
    } catch (final RuntimeException exception) {
      throw new InvalidAuditEventException("audit event payload is invalid", exception);
    }
  }

  private static AuditCategory toContractCategory(final AuditEventCategory category) {
    return switch (category) {
      case AUTHENTICATION -> AuditCategory.AUDIT_CATEGORY_AUTHENTICATION;
      case AUTHORIZATION -> AuditCategory.AUDIT_CATEGORY_AUTHORIZATION;
      case DATA_ACCESS -> AuditCategory.AUDIT_CATEGORY_DATA_ACCESS;
      case PHI_SAFETY -> AuditCategory.AUDIT_CATEGORY_PHI_SAFETY;
      case GOVERNANCE -> AuditCategory.AUDIT_CATEGORY_GOVERNANCE;
      case SYSTEM -> AuditCategory.AUDIT_CATEGORY_SYSTEM;
    };
  }

  private static AuditEventCategory fromContractCategory(final AuditCategory category) {
    return switch (category) {
      case AUDIT_CATEGORY_AUTHENTICATION -> AuditEventCategory.AUTHENTICATION;
      case AUDIT_CATEGORY_AUTHORIZATION -> AuditEventCategory.AUTHORIZATION;
      case AUDIT_CATEGORY_DATA_ACCESS -> AuditEventCategory.DATA_ACCESS;
      case AUDIT_CATEGORY_PHI_SAFETY -> AuditEventCategory.PHI_SAFETY;
      case AUDIT_CATEGORY_GOVERNANCE -> AuditEventCategory.GOVERNANCE;
      case AUDIT_CATEGORY_SYSTEM -> AuditEventCategory.SYSTEM;
      case AUDIT_CATEGORY_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidAuditEventException("audit event category is required");
    };
  }
}
