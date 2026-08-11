package com.medassist.clinicaldata.persistence;

/** Stable failure type for the explicit clinical persistence boundary. */
public final class ClinicalDataPersistenceException extends RuntimeException {
  public ClinicalDataPersistenceException(final String message) {
    super(message);
  }

  public ClinicalDataPersistenceException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
