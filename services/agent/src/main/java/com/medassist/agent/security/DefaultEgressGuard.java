package com.medassist.agent.security;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class DefaultEgressGuard implements EgressGuard {
  private final EgressPolicy policy;

  public DefaultEgressGuard() {
    this(EgressPolicy.defaults());
  }

  public DefaultEgressGuard(final EgressPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public EgressDecision inspect(final EgressRequest request) {
    if (request == null) {
      return decision(
          false, EgressReason.NULL_REQUEST, null, ContentClass.UNKNOWN, EgressSource.UNKNOWN, null);
    }

    final String destination = normalizeDestination(request.destination());
    final ContentClass contentClass =
        request.contentClass() == null ? ContentClass.UNKNOWN : request.contentClass();
    final EgressSource source = request.source() == null ? EgressSource.UNKNOWN : request.source();
    if (destination == null || !policy.knownDestinations().contains(destination)) {
      return decision(
          false,
          EgressReason.UNKNOWN_DESTINATION,
          destination,
          contentClass,
          source,
          request.payload());
    }
    if (!policy.allowedDestinations().contains(destination)) {
      return decision(
          false,
          EgressReason.DESTINATION_NOT_ALLOWED,
          destination,
          contentClass,
          source,
          request.payload());
    }
    if (contentClass == ContentClass.UNKNOWN) {
      return decision(
          false,
          EgressReason.UNKNOWN_CONTENT_CLASS,
          destination,
          contentClass,
          source,
          request.payload());
    }
    if (!policy.allowedContentClasses().contains(contentClass)) {
      return decision(
          false,
          EgressReason.CONTENT_CLASS_NOT_ALLOWED,
          destination,
          contentClass,
          source,
          request.payload());
    }
    if (request.payload() == null || request.payload().isBlank()) {
      return decision(
          false,
          EgressReason.INVALID_PAYLOAD,
          destination,
          contentClass,
          source,
          request.payload());
    }
    if (request.rawUserQuestion()) {
      return decision(
          false,
          EgressReason.RAW_USER_QUESTION,
          destination,
          contentClass,
          source,
          request.payload());
    }

    final Set<SensitiveFinding> findings = SensitiveContentScanner.find(request.payload());
    if (!findings.isEmpty()) {
      return decision(
          false,
          EgressReason.SENSITIVE_CONTENT,
          destination,
          contentClass,
          source,
          request.payload(),
          findings);
    }
    return decision(
        true, EgressReason.ALLOWED, destination, contentClass, source, request.payload());
  }

  public EgressDecision check(final EgressRequest request) {
    return inspect(request);
  }

  private EgressDecision decision(
      final boolean allowed,
      final EgressReason reason,
      final String destination,
      final ContentClass contentClass,
      final EgressSource source,
      final String payload) {
    return decision(allowed, reason, destination, contentClass, source, payload, Set.of());
  }

  private EgressDecision decision(
      final boolean allowed,
      final EgressReason reason,
      final String destination,
      final ContentClass contentClass,
      final EgressSource source,
      final String payload,
      final Set<SensitiveFinding> findings) {
    final SecurityAuditEvent event =
        new SecurityAuditEvent(
            allowed ? "ALLOW" : "DENY",
            destination == null ? "UNKNOWN" : destination,
            contentClass,
            source,
            reason,
            Hashing.sha256(payload),
            findings);
    return new EgressDecision(allowed, reason, event);
  }

  private static String normalizeDestination(final String destination) {
    if (destination == null || destination.isBlank()) {
      return null;
    }
    return destination.trim().toUpperCase(Locale.ROOT);
  }
}
