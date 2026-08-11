package com.medassist.ingestion.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class SkipLimitTaskletTest {

  @Test
  void permitsSkipCountEqualToLimit() throws Exception {
    final SkipLimitTasklet tasklet =
        new SkipLimitTasklet((contribution, context) -> RepeatStatus.FINISHED, 2);

    final RepeatStatus result =
        tasklet.execute(contributionWithSkipCount(2), mock(ChunkContext.class));

    assertThat(result).isEqualTo(RepeatStatus.FINISHED);
  }

  @Test
  void failsWhenSkipCountExceedsLimit() {
    final SkipLimitTasklet tasklet =
        new SkipLimitTasklet((contribution, context) -> RepeatStatus.FINISHED, 2);

    assertThatThrownBy(
            () -> tasklet.execute(contributionWithSkipCount(3), mock(ChunkContext.class)))
        .isInstanceOf(SkipLimitTasklet.SkipLimitExceededException.class)
        .hasMessage("ingestion skip limit exceeded");
  }

  private static StepContribution contributionWithSkipCount(final long skipCount) {
    final StepContribution contribution = mock(StepContribution.class);
    final StepExecution execution = mock(StepExecution.class);
    when(contribution.getStepExecution()).thenReturn(execution);
    when(execution.getSkipCount()).thenReturn(skipCount);
    return contribution;
  }
}
