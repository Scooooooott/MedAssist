package com.medassist.ingestion.discovery;

/** Base checked exception for object discovery failures. */
public abstract class DiscoveryException extends Exception {
  protected DiscoveryException(final String message) {
    super(message);
  }

  protected DiscoveryException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
