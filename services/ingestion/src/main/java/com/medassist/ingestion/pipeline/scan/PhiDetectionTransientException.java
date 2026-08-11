package com.medassist.ingestion.pipeline.scan;

/** A retryable PHI detection failure. */
public final class PhiDetectionTransientException extends PhiDetectionException {
  public PhiDetectionTransientException(final String message) {
    super(message);
  }
}
