package com.medassist.ingestion.batch;

import java.time.Duration;
import java.util.Objects;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.retry.support.RetryTemplate;

/** Retries only failures that escape a tasklet's permanent-failure quarantine boundary. */
final class RetryingTasklet implements Tasklet {
  private final Tasklet delegate;
  private final RetryTemplate retries;

  RetryingTasklet(
      final Tasklet delegate,
      final int maxAttempts,
      final Duration initialBackoff,
      final Duration maxBackoff,
      final double multiplier) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (maxAttempts < 1
        || initialBackoff == null
        || initialBackoff.isNegative()
        || initialBackoff.isZero()
        || maxBackoff == null
        || maxBackoff.compareTo(initialBackoff) < 0
        || multiplier <= 1.0) {
      throw new IllegalArgumentException("retry settings are invalid");
    }
    this.retries =
        RetryTemplate.builder()
            .maxAttempts(maxAttempts)
            .exponentialBackoff(initialBackoff.toMillis(), multiplier, maxBackoff.toMillis())
            .retryOn(Exception.class)
            .build();
  }

  @Override
  public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext)
      throws Exception {
    return retries.execute(context -> delegate.execute(contribution, chunkContext));
  }
}
