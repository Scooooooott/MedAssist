package com.medassist.ingestion.batch;

import java.util.Objects;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** Fails the current job when quarantined document count exceeds the configured limit. */
final class SkipLimitTasklet implements Tasklet {
  private final Tasklet delegate;
  private final long skipLimit;

  SkipLimitTasklet(final Tasklet delegate, final long skipLimit) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (skipLimit < 0) {
      throw new IllegalArgumentException("skipLimit must be non-negative");
    }
    this.skipLimit = skipLimit;
  }

  @Override
  public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext)
      throws Exception {
    final RepeatStatus status = delegate.execute(contribution, chunkContext);
    if (contribution.getStepExecution().getSkipCount() > skipLimit) {
      throw new SkipLimitExceededException();
    }
    return status;
  }

  static final class SkipLimitExceededException extends RuntimeException {
    private SkipLimitExceededException() {
      super("ingestion skip limit exceeded");
    }
  }
}
