package com.medassist.agent.api.generation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.medassist.agent.generation.GenerationSession;
import com.medassist.agent.generation.GenerationStatus;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationSessionResponse(
    @JsonProperty("generation_id") String generationId,
    GenerationStatus status,
    @JsonProperty("expires_at") Instant expiresAt,
    @JsonProperty("events_url") String eventsUrl,
    @JsonProperty("terminal_event_id") String terminalEventId) {
  public static GenerationSessionResponse from(final GenerationSession session) {
    return new GenerationSessionResponse(
        session.generationId(),
        session.status(),
        session.expiresAt(),
        "/api/generations/" + session.generationId() + "/events",
        session.terminalEventId());
  }
}
