package com.medassist.ingestion.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

class IngestionBatchConfigurationTest {
  @Test
  void releasesMutexAfterCompletedJobExecution() {
    assertMutexReleasedAfter(BatchStatus.COMPLETED);
  }

  @Test
  void releasesMutexAfterFailedJobExecution() {
    assertMutexReleasedAfter(BatchStatus.FAILED);
  }

  private static void assertMutexReleasedAfter(final BatchStatus status) {
    final IngestionJobMutex mutex = new IngestionJobMutex();
    final JobExecution execution = mock(JobExecution.class);
    when(execution.getStatus()).thenReturn(status);
    final JobExecutionListener listener =
        new IngestionBatchConfiguration().ingestionJobMutexListener(mutex);
    assertThat(mutex.tryAcquire()).isTrue();

    listener.afterJob(execution);

    assertThat(mutex.tryAcquire()).isTrue();
  }
}
