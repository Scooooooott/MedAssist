package com.medassist.agent.security;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record EgressPolicy(
    Set<String> knownDestinations,
    Set<String> allowedDestinations,
    Set<ContentClass> allowedContentClasses) {
  public EgressPolicy {
    knownDestinations = normalizeDestinations(knownDestinations, "knownDestinations");
    allowedDestinations = normalizeDestinations(allowedDestinations, "allowedDestinations");
    allowedContentClasses =
        Set.copyOf(Objects.requireNonNull(allowedContentClasses, "allowedContentClasses"));
    if (!knownDestinations.containsAll(allowedDestinations)) {
      throw new IllegalArgumentException("allowed destinations must be known destinations");
    }
  }

  public EgressPolicy(
      final Set<String> allowedDestinations, final Set<ContentClass> allowedContentClasses) {
    this(allowedDestinations, allowedDestinations, allowedContentClasses);
  }

  public static EgressPolicy defaults() {
    return new EgressPolicy(
        Set.of("LOCAL_MODEL", "EXTERNAL_LLM"),
        Set.of("LOCAL_MODEL", "EXTERNAL_LLM"),
        Set.of(
            ContentClass.DEIDENTIFIED_QUERY,
            ContentClass.SAFE_CHUNK_METADATA,
            ContentClass.AGGREGATE_ONLY));
  }

  private static Set<String> normalizeDestinations(
      final Set<String> destinations, final String fieldName) {
    Objects.requireNonNull(destinations, fieldName);
    final Set<String> normalized = new HashSet<>();
    for (final String destination : destinations) {
      if (destination == null || destination.isBlank()) {
        throw new IllegalArgumentException(fieldName + " cannot contain blank destinations");
      }
      normalized.add(destination.trim().toUpperCase(Locale.ROOT));
    }
    return Set.copyOf(normalized);
  }
}
