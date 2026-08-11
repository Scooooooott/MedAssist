package com.medassist.ingestion.discovery;

/** A discovery failure that may succeed when retried. */
public final class DiscoveryTransientException extends DiscoveryException {
  public DiscoveryTransientException(final String message) {
    super(message);
  }

  public DiscoveryTransientException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
