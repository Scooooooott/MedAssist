package com.medassist.ingestion.batch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunParameters;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunStatus;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditStep;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.StepAggregate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class IngestionAuditListenerTest {
  private static final UUID RUN_ID = UUID.randomUUID();

  @Test
  void lifecycleStoresOnlyRunUuidInExecutionContextAndFinishesSuccessfully() {
    final IngestionAuditRepository repository = mock(IngestionAuditRepository.class);
    final JobExecution execution = jobExecution(BatchStatus.COMPLETED);
    final IngestionAuditListener listener = new IngestionAuditListener(repository);

    listener.beforeJob(execution);
    listener.afterJob(execution);

    final ArgumentCaptor<AuditRunParameters> parameters =
        ArgumentCaptor.forClass(AuditRunParameters.class);
    final ArgumentCaptor<UUID> runId = ArgumentCaptor.forClass(UUID.class);
    verify(repository).startRun(runId.capture(), parameters.capture());
    verify(repository).finishRun(runId.getValue(), AuditRunStatus.COMPLETED);
    assertThat(IngestionAuditListener.RUN_ID_CONTEXT_KEY).isEqualTo("medassist.ingestion.run-id");
    assertThat(parameters.getValue().sourceScope()).isEqualTo("all");
    assertThat(parameters.getValue().forceReprocess()).isTrue();
    assertThat(execution.getExecutionContext().toMap())
        .containsOnly(
            org.assertj.core.api.Assertions.entry(
                IngestionAuditListener.RUN_ID_CONTEXT_KEY, runId.getValue().toString()));
  }

  @Test
  void failedJobWritesFailedTerminalStatus() {
    final IngestionAuditRepository repository = mock(IngestionAuditRepository.class);
    final JobExecution execution = jobExecution(BatchStatus.FAILED);
    execution
        .getExecutionContext()
        .putString(IngestionAuditListener.RUN_ID_CONTEXT_KEY, RUN_ID.toString());

    new IngestionAuditListener(repository).afterJob(execution);

    verify(repository).finishRun(RUN_ID, AuditRunStatus.FAILED);
  }

  @Test
  void afterStepForwardsCountsAndDurationForEachAllowedStep() {
    for (final AuditStep auditStep : AuditStep.values()) {
      final IngestionAuditRepository repository = mock(IngestionAuditRepository.class);
      final IngestionAuditListener listener = new IngestionAuditListener(repository);
      final JobExecution execution = jobExecution(BatchStatus.STARTED);
      execution
          .getExecutionContext()
          .putString(IngestionAuditListener.RUN_ID_CONTEXT_KEY, RUN_ID.toString());
      final StepExecution step = stepExecution(execution, auditStep.batchStepName());

      assertThat(listener.afterStep(step)).isEqualTo(ExitStatus.COMPLETED);

      verify(repository).updateStepAggregate(RUN_ID, new StepAggregate(auditStep, 11, 7, 3, 1250));
    }
  }

  @Test
  void unknownStepFailsClosedWithoutRepositoryWrite() {
    final IngestionAuditRepository repository = mock(IngestionAuditRepository.class);
    final JobExecution execution = jobExecution(BatchStatus.STARTED);
    execution
        .getExecutionContext()
        .putString(IngestionAuditListener.RUN_ID_CONTEXT_KEY, RUN_ID.toString());

    assertThatThrownBy(
            () ->
                new IngestionAuditListener(repository)
                    .afterStep(stepExecution(execution, "untrusted-step")))
        .isInstanceOf(AuditPersistenceException.class)
        .hasMessage("unknown ingestion step");
    verify(repository, never()).updateStepAggregate(any(), any());
  }

  private static JobExecution jobExecution(final BatchStatus status) {
    final JobExecution execution = mock(JobExecution.class);
    final JobParameters parameters = mock(JobParameters.class);
    final ExecutionContext context = new ExecutionContext();
    when(parameters.getString("requestedAt")).thenReturn("2026-08-07T10:15:30Z");
    when(parameters.getString("sourceScope")).thenReturn("all");
    when(parameters.getString("forceReprocess")).thenReturn("true");
    when(execution.getJobParameters()).thenReturn(parameters);
    final JobInstance instance = mock(JobInstance.class);
    when(instance.getId()).thenReturn(17L);
    when(execution.getJobInstance()).thenReturn(instance);
    when(execution.getExecutionContext()).thenReturn(context);
    when(execution.getStatus()).thenReturn(status);
    return execution;
  }

  private static StepExecution stepExecution(
      final JobExecution jobExecution, final String stepName) {
    final StepExecution execution = mock(StepExecution.class);
    when(execution.getJobExecution()).thenReturn(jobExecution);
    when(execution.getStepName()).thenReturn(stepName);
    when(execution.getReadCount()).thenReturn(11L);
    when(execution.getWriteCount()).thenReturn(7L);
    when(execution.getSkipCount()).thenReturn(3L);
    when(execution.getStartTime()).thenReturn(LocalDateTime.of(2026, 8, 7, 10, 0, 0));
    when(execution.getEndTime()).thenReturn(LocalDateTime.of(2026, 8, 7, 10, 0, 1, 250_000_000));
    when(execution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
    return execution;
  }
}
