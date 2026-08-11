package com.medassist.ingestion.pipeline.parse;

/** Parser failure caused by invalid or permanently unsupported content. */
public final class ParserPermanentException extends ParserException {
  public ParserPermanentException(final String message) {
    super(message);
  }

  public ParserPermanentException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
