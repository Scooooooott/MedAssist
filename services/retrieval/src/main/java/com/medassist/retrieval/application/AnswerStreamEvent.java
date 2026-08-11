package com.medassist.retrieval.application;

import com.medassist.retrieval.api.dto.AnswerResponse;

public record AnswerStreamEvent(
    String delta, AnswerResponse finalResponse, RetryStatus retryStatus) {
  public AnswerStreamEvent {
    if ((delta == null ? 0 : 1) + (finalResponse == null ? 0 : 1) + (retryStatus == null ? 0 : 1)
        != 1) {
      throw new IllegalArgumentException("exactly one answer stream event value is required");
    }
  }

  public AnswerStreamEvent(final String delta, final AnswerResponse finalResponse) {
    this(delta, finalResponse, null);
  }

  public static AnswerStreamEvent delta(final String value) {
    return new AnswerStreamEvent(value, null);
  }

  public static AnswerStreamEvent complete(final AnswerResponse response) {
    return new AnswerStreamEvent(null, response, null);
  }

  public static AnswerStreamEvent retry(
      final int attempt, final int maxAttempts, final String reason) {
    return new AnswerStreamEvent(null, null, new RetryStatus(attempt, maxAttempts, reason));
  }

  public boolean isDelta() {
    return delta != null;
  }

  public boolean isRetry() {
    return retryStatus != null;
  }
}
