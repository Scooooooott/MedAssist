package com.medassist.common.resilience;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Safe default retry classification for transient transport failures. */
@FunctionalInterface
public interface RetryableFailureClassifier extends Predicate<Throwable> {
  static RetryableFailureClassifier transportFailures() {
    return failure -> {
      final Throwable cause = unwrap(failure);
      if (cause instanceof IOException || cause instanceof TimeoutException) {
        return true;
      }
      if (cause instanceof StatusRuntimeException statusFailure) {
        final Status.Code code = statusFailure.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE
            || code == Status.Code.DEADLINE_EXCEEDED
            || code == Status.Code.RESOURCE_EXHAUSTED
            || code == Status.Code.INTERNAL;
      }
      return false;
    };
  }

  private static Throwable unwrap(final Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null
        && (current instanceof ResilienceExecutionException
            || current instanceof java.util.concurrent.ExecutionException)) {
      current = current.getCause();
    }
    return current;
  }
}
