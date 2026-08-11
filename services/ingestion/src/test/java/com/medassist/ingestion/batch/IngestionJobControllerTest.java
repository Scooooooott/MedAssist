package com.medassist.ingestion.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.http.HttpStatus;

class IngestionJobControllerTest {
  private final JobOperator jobOperator = mock(JobOperator.class);
  private final JobRepository jobRepository = mock(JobRepository.class);
  private final Job job = mock(Job.class);
  private final Job contextBackfillJob = mock(Job.class);
  private final IngestionJobMutex mutex = new IngestionJobMutex();
  private final IngestionJobService jobService =
      new IngestionJobService(jobOperator, jobRepository, job, contextBackfillJob, mutex);
  private final IngestionJobController controller = new IngestionJobController(jobService);

  @Test
  void rejectsConcurrentLaunchWithSafeFixedMessage() throws Exception {
    final JobExecution execution = startedExecution(41L);
    when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(execution);

    assertThat(controller.trigger(null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    final var rejected = controller.trigger(null);

    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(rejected.getBody())
        .isEqualTo(
            new IngestionJobController.IngestionJobResponse(
                null, "ALREADY_RUNNING", "An ingestion job is already running."));
    verify(jobOperator, times(1)).start(any(Job.class), any(JobParameters.class));
  }

  @Test
  void releasesMutexAndDoesNotLeakMessageWhenLaunchFails() throws Exception {
    final JobExecution execution = startedExecution(42L);
    when(jobOperator.start(any(Job.class), any(JobParameters.class)))
        .thenThrow(new IllegalStateException("database-password=secret"))
        .thenReturn(execution);

    final var failed = controller.trigger(null);
    final var retried = controller.trigger(null);

    assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(failed.getBody())
        .isEqualTo(
            new IngestionJobController.IngestionJobResponse(
                null, "START_FAILED", "Unable to start ingestion job."));
    assertThat(failed.getBody().message()).doesNotContain("database-password", "secret");
    assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void normalizesBlankScopeAndPreservesForceReprocess() throws Exception {
    final JobExecution execution = startedExecution(43L);
    when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    final ArgumentCaptor<JobParameters> parameters = ArgumentCaptor.forClass(JobParameters.class);

    controller.trigger(new IngestionJobController.IngestionJobRequest("  ", true));

    verify(jobOperator).start(any(Job.class), parameters.capture());
    assertThat(parameters.getValue().getString("sourceScope")).isEqualTo("all");
    assertThat(parameters.getValue().getString("forceReprocess")).isEqualTo("true");
  }

  @Test
  void restartsFailedExecutionUsingThePersistedExecution() throws Exception {
    final JobExecution previous = execution(51L, BatchStatus.FAILED);
    final JobExecution restarted = execution(52L, BatchStatus.STARTED);
    when(jobRepository.getJobExecution(51L)).thenReturn(previous);
    when(jobOperator.restart(previous)).thenReturn(restarted);

    final var response = controller.restart(51L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody())
        .isEqualTo(new IngestionJobController.IngestionJobResponse(52L, "STARTED", "restarted"));
    verify(jobOperator).restart(previous);
  }

  @Test
  void rejectsCompletedExecutionWithoutLaunching() throws Exception {
    final JobExecution previous = execution(53L, BatchStatus.COMPLETED);
    when(jobRepository.getJobExecution(53L)).thenReturn(previous);

    final var response = controller.restart(53L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new IngestionJobController.IngestionJobResponse(
                53L, "NOT_RESTARTABLE", "Only failed or stopped ingestion jobs can be restarted."));
    verify(jobOperator, never()).restart(any(JobExecution.class));
  }

  @Test
  void rejectsUnknownOrForeignExecution() throws Exception {
    when(jobRepository.getJobExecution(54L)).thenReturn(null);

    final var response = controller.restart(54L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .isEqualTo(
            new IngestionJobController.IngestionJobResponse(
                null, "EXECUTION_NOT_FOUND", "Ingestion job execution was not found."));
    verify(jobOperator, never()).restart(any(JobExecution.class));
  }

  @Test
  void releasesMutexAndUsesFixedMessageWhenRestartFails() throws Exception {
    final JobExecution previous = execution(55L, BatchStatus.STOPPED);
    final JobExecution later = execution(56L, BatchStatus.STARTED);
    when(jobRepository.getJobExecution(55L)).thenReturn(previous);
    when(jobOperator.restart(previous))
        .thenThrow(new IllegalStateException("database-password=secret"));
    when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(later);

    final var failed = controller.restart(55L);
    final var newLaunch = controller.trigger(null);

    assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(failed.getBody())
        .isEqualTo(
            new IngestionJobController.IngestionJobResponse(
                null, "START_FAILED", "Unable to start ingestion job."));
    assertThat(failed.getBody().message()).doesNotContain("database-password", "secret");
    assertThat(newLaunch.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void startsContextBackfillAsItsOwnBatchJob() throws Exception {
    final JobExecution execution = startedExecution(57L);
    when(jobOperator.start(
            org.mockito.ArgumentMatchers.eq(contextBackfillJob), any(JobParameters.class)))
        .thenReturn(execution);

    final var response = controller.triggerContextBackfill();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody())
        .isEqualTo(new IngestionJobController.IngestionJobResponse(57L, "STARTED", "started"));
    verify(jobOperator)
        .start(org.mockito.ArgumentMatchers.eq(contextBackfillJob), any(JobParameters.class));
  }

  private static JobExecution startedExecution(final long id) {
    return execution(id, BatchStatus.STARTED);
  }

  private static JobExecution execution(final long id, final BatchStatus status) {
    final JobExecution execution = mock(JobExecution.class);
    final JobInstance jobInstance = mock(JobInstance.class);
    when(execution.getId()).thenReturn(id);
    when(execution.getStatus()).thenReturn(status);
    when(execution.getJobInstance()).thenReturn(jobInstance);
    when(jobInstance.getJobName()).thenReturn("documentIngestionJob");
    return execution;
  }
}
