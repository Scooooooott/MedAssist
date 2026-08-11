package com.medassist.ingestion.pipeline.parse;

/** Parser failure that may succeed on retry, including a timeout. */
public final class ParserTransientException extends ParserException {
  public ParserTransientException(final String message) {
    super(message);
  }

  public ParserTransientException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
