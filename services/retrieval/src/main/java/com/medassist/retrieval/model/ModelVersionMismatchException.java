package com.medassist.retrieval.model;

public final class ModelVersionMismatchException extends RuntimeException {
  public ModelVersionMismatchException(final String message) {
    super(message);
  }
}
