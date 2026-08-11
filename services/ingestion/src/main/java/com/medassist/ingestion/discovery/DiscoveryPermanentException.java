package com.medassist.ingestion.discovery;

/** A discovery failure caused by invalid input or an unrecoverable data error. */
public final class DiscoveryPermanentException extends DiscoveryException {
  public DiscoveryPermanentException(final String message) {
    super(message);
  }

  public DiscoveryPermanentException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
