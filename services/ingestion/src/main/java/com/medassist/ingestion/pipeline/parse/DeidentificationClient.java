package com.medassist.ingestion.pipeline.parse;

/** Narrow port for de-identification sidecar calls. */
@FunctionalInterface
public interface DeidentificationClient {
  DeidentificationResponse anonymize(DeidentificationRequest request)
      throws DeidentificationException;
}
