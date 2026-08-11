package com.medassist.ingestion.pipeline.parse;

/** Base checked failure for parser calls. */
public abstract class ParserException extends Exception {
  protected ParserException(final String message) {
    super(message);
  }

  protected ParserException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
