package com.medassist.agent.generation;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Metadata-only session resource; queries and output content are deliberately absent. */
public record GenerationSession(
    String generationId,
    String ownerSubject,
    Set<String> roles,
    String policyVersion,
    String requestHash,
    GenerationStatus status,
    Instant createdAt,
    Instant expiresAt,
    String terminalEventId) {
  public GenerationSession {
    generationId = requireText(generationId, "generationId");
    ownerSubject = requireText(ownerSubject, "ownerSubject");
    roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    if (roles.isEmpty()) {
      throw new IllegalArgumentException("roles must not be empty");
    }
    policyVersion = requireText(policyVersion, "policyVersion");
    requestHash = requireText(requestHash, "requestHash");
    status = Objects.requireNonNull(status, "status");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("expiresAt must be after createdAt");
    }
    if (terminalEventId != null && terminalEventId.isBlank()) {
      terminalEventId = null;
    }
  }

  public GenerationSession withStatus(
      final GenerationStatus nextStatus, final String nextTerminalEventId) {
    return new GenerationSession(
        generationId,
        ownerSubject,
        roles,
        policyVersion,
        requestHash,
        nextStatus,
        createdAt,
        expiresAt,
        nextTerminalEventId);
  }

  private static String requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
