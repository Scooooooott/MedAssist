package com.medassist.ingestion.discovery;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Lists, fingerprints, and classifies object-store objects for ingestion. */
public final class ObjectDiscoveryService {
  private static final Comparator<ObjectDescriptor> DETERMINISTIC_ORDER =
      Comparator.comparing(ObjectDescriptor::sourceId)
          .thenComparing(object -> object.storageUri().toString())
          .thenComparing(ObjectDescriptor::mimeType)
          .thenComparingLong(ObjectDescriptor::size);

  private final ObjectStoreCatalog catalog;
  private final DocumentFingerprintRepository fingerprintRepository;
  private final Sha256Hasher hasher;

  public ObjectDiscoveryService(
      final ObjectStoreCatalog catalog,
      final DocumentFingerprintRepository fingerprintRepository,
      final Sha256Hasher hasher) {
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    this.fingerprintRepository =
        Objects.requireNonNull(fingerprintRepository, "fingerprintRepository must not be null");
    this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
  }

  public List<ObjectDiscoveryResult> discover(final boolean forceReprocess)
      throws DiscoveryException {
    final List<ObjectDescriptor> objects = catalog.listObjects();
    if (objects == null) {
      throw new DiscoveryPermanentException("ObjectStoreCatalog returned null object list");
    }

    final List<ObjectDescriptor> orderedObjects = new ArrayList<>(objects);
    if (orderedObjects.stream().anyMatch(Objects::isNull)) {
      throw new DiscoveryPermanentException("ObjectStoreCatalog returned a null object descriptor");
    }
    orderedObjects.sort(DETERMINISTIC_ORDER);
    rejectDuplicateUris(orderedObjects);

    final List<ObjectDiscoveryResult> results = new ArrayList<>(orderedObjects.size());
    for (final ObjectDescriptor object : orderedObjects) {
      final String currentFingerprint = hasher.hash(object);
      final Optional<String> previousFingerprint =
          fingerprintRepository.findFingerprint(object.sourceId(), object.storageUri());
      if (previousFingerprint == null) {
        throw new DiscoveryPermanentException(
            "DocumentFingerprintRepository returned null Optional for " + object.storageUri());
      }
      try {
        previousFingerprint.ifPresent(
            fingerprint -> validateFingerprint(fingerprint, object.storageUri()));
      } catch (final IllegalArgumentException exception) {
        throw new DiscoveryPermanentException(exception.getMessage(), exception);
      }
      final DiscoveryClassification classification =
          classify(currentFingerprint, previousFingerprint);
      results.add(
          new ObjectDiscoveryResult(
              object,
              currentFingerprint,
              previousFingerprint,
              classification,
              forceReprocess || classification != DiscoveryClassification.UNCHANGED));
    }
    return List.copyOf(results);
  }

  private static DiscoveryClassification classify(
      final String currentFingerprint, final Optional<String> previousFingerprint) {
    if (previousFingerprint.isEmpty()) {
      return DiscoveryClassification.NEW;
    }
    return currentFingerprint.equalsIgnoreCase(previousFingerprint.get())
        ? DiscoveryClassification.UNCHANGED
        : DiscoveryClassification.CHANGED;
  }

  private static void validateFingerprint(final String fingerprint, final URI storageUri) {
    if (!fingerprint.matches("[0-9a-fA-F]{64}")) {
      throw new IllegalArgumentException(
          "Invalid SHA-256 fingerprint for " + storageUri + ": " + fingerprint);
    }
  }

  private static void rejectDuplicateUris(final List<ObjectDescriptor> objects)
      throws DiscoveryPermanentException {
    final Set<URI> seenUris = new HashSet<>();
    for (final ObjectDescriptor object : objects) {
      if (!seenUris.add(object.storageUri())) {
        throw new DiscoveryPermanentException(
            "ObjectStoreCatalog returned duplicate storage URI: " + object.storageUri());
      }
    }
  }
}
