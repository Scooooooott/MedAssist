package com.medassist.ingestion.pipeline.scan;

/** Safe failure raised when a post-de-identification PHI scan cannot be trusted. */
public abstract class PhiDetectionException extends Exception {
  protected PhiDetectionException(final String message) {
    super(message);
  }
}
