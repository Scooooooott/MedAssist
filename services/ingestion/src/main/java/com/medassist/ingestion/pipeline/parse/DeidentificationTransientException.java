package com.medassist.ingestion.pipeline.parse;

/** De-identification failure that may succeed on retry, including a timeout. */
public final class DeidentificationTransientException extends DeidentificationException {
  public DeidentificationTransientException(final String message) {
    super(message);
  }

  public DeidentificationTransientException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
