package com.medassist.ingestion.pipeline.scan;

/** A non-retryable PHI detection failure. */
public final class PhiDetectionPermanentException extends PhiDetectionException {
  public PhiDetectionPermanentException(final String message) {
    super(message);
  }
}
