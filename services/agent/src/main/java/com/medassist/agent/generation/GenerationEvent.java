package com.medassist.agent.generation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A Redis Stream event containing only approved client output or safe metadata. */
public record GenerationEvent(
    String eventId,
    String generationId,
    GenerationEventType type,
    String schemaVersion,
    Instant createdAt,
    Map<String, Object> payload) {
  public static final String CURRENT_SCHEMA_VERSION = "1";

  public GenerationEvent {
    if (eventId != null && eventId.isBlank()) {
      throw new IllegalArgumentException("eventId must not be blank");
    }
    if (generationId == null || generationId.isBlank()) {
      throw new IllegalArgumentException("generationId is required");
    }
    type = Objects.requireNonNull(type, "type");
    schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
  }

  public GenerationEvent withEventId(final String id) {
    return new GenerationEvent(id, generationId, type, schemaVersion, createdAt, payload);
  }

  public boolean terminal() {
    return type == GenerationEventType.FINAL
        || type == GenerationEventType.ERROR
        || type == GenerationEventType.CANCELLED;
  }

  @Override
  public String toString() {
    return "GenerationEvent[eventId="
        + eventId
        + ", generationId="
        + generationId
        + ", type="
        + type
        + ", schemaVersion="
        + schemaVersion
        + ", createdAt="
        + createdAt
        + ", payload=<redacted>]";
  }
}
