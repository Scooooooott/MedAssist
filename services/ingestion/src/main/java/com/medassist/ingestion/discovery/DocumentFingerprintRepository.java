package com.medassist.ingestion.discovery;

import java.net.URI;
import java.util.Optional;

/** Port for reading the last successfully indexed fingerprint of an object. */
public interface DocumentFingerprintRepository {
  Optional<String> findFingerprint(String sourceId, URI storageUri) throws DiscoveryException;
}
