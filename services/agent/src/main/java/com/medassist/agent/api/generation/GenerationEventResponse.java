package com.medassist.agent.api.generation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medassist.agent.generation.GenerationEvent;
import java.time.Instant;
import java.util.Map;

public record GenerationEventResponse(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("generation_id") String generationId,
    String type,
    @JsonProperty("schema_version") String schemaVersion,
    @JsonProperty("created_at") Instant createdAt,
    Map<String, Object> payload) {
  public static GenerationEventResponse from(final GenerationEvent event) {
    return new GenerationEventResponse(
        event.eventId(),
        event.generationId(),
        event.type().wireName(),
        event.schemaVersion(),
        event.createdAt(),
        event.payload());
  }
}
