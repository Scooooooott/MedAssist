package com.medassist.ingestion.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class RetryingTaskletTest {

  @Test
  void retriesEscapingFailureUntilDelegateSucceeds() throws Exception {
    final AtomicInteger attempts = new AtomicInteger();
    final RetryingTasklet tasklet =
        tasklet(
            (contribution, context) -> {
              if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
              }
              return RepeatStatus.FINISHED;
            },
            3);

    final RepeatStatus result =
        tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

    assertThat(result).isEqualTo(RepeatStatus.FINISHED);
    assertThat(attempts).hasValue(3);
  }

  @Test
  void rethrowsAfterConfiguredAttemptLimit() {
    final AtomicInteger attempts = new AtomicInteger();
    final RetryingTasklet tasklet =
        tasklet(
            (contribution, context) -> {
              attempts.incrementAndGet();
              throw new IllegalStateException("transient");
            },
            2);

    assertThatThrownBy(
            () -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("transient");
    assertThat(attempts).hasValue(2);
  }

  private static RetryingTasklet tasklet(
      final org.springframework.batch.core.step.tasklet.Tasklet delegate, final int maxAttempts) {
    return new RetryingTasklet(
        delegate, maxAttempts, Duration.ofMillis(1), Duration.ofMillis(2), 2.0);
  }
}
