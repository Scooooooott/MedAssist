package com.medassist.ingestion.pipeline.parse;

/** Base checked failure for de-identification calls. */
public abstract class DeidentificationException extends Exception {
  protected DeidentificationException(final String message) {
    super(message);
  }

  protected DeidentificationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
