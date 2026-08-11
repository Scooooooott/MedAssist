package com.medassist.ingestion.pipeline.parse;

/** De-identification failure caused by invalid policy or unrecoverable content. */
public final class DeidentificationPermanentException extends DeidentificationException {
  public DeidentificationPermanentException(final String message) {
    super(message);
  }

  public DeidentificationPermanentException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
