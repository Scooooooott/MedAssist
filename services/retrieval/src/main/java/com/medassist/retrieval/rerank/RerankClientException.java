package com.medassist.retrieval.rerank;

/** Safe, classified failure from a reranker backend. */
public final class RerankClientException extends RuntimeException {
  public enum Reason {
    TIMEOUT,
    BACKEND_ERROR
  }

  private final Reason reason;

  private RerankClientException(final Reason reason, final String message, final Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public static RerankClientException timeout(final String message, final Throwable cause) {
    return new RerankClientException(Reason.TIMEOUT, message, cause);
  }

  public static RerankClientException backend(final String message, final Throwable cause) {
    return new RerankClientException(Reason.BACKEND_ERROR, message, cause);
  }

  public Reason reason() {
    return reason;
  }
}
