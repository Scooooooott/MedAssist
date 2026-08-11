package com.medassist.ingestion.discovery;

import java.util.Objects;
import java.util.Optional;

/** Classification and processing decision for one discovered object. */
public record ObjectDiscoveryResult(
    ObjectDescriptor object,
    String currentFingerprint,
    Optional<String> previousFingerprint,
    DiscoveryClassification classification,
    boolean processRequired) {

  public ObjectDiscoveryResult {
    object = Objects.requireNonNull(object, "object must not be null");
    currentFingerprint =
        Objects.requireNonNull(currentFingerprint, "currentFingerprint must not be null");
    previousFingerprint =
        Objects.requireNonNull(previousFingerprint, "previousFingerprint must not be null");
    classification = Objects.requireNonNull(classification, "classification must not be null");
    if (classification == DiscoveryClassification.NEW && previousFingerprint.isPresent()) {
      throw new IllegalArgumentException("NEW objects cannot have a previous fingerprint");
    }
    if (classification != DiscoveryClassification.NEW && previousFingerprint.isEmpty()) {
      throw new IllegalArgumentException("non-NEW objects require a previous fingerprint");
    }
  }
}
