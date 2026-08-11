package com.medassist.ingestion.batch.audit;

import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunParameters;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditRunStatus;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.AuditStep;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository.StepAggregate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/** Persists job and step audit metadata without retaining document content. */
public final class IngestionAuditListener implements JobExecutionListener, StepExecutionListener {
  public static final String RUN_ID_CONTEXT_KEY = "medassist.ingestion.run-id";

  private final IngestionAuditRepository repository;

  public IngestionAuditListener(final IngestionAuditRepository repository) {
    this.repository = repository;
  }

  @Override
  public void beforeJob(final JobExecution jobExecution) {
    final var parameters = jobExecution.getJobParameters();
    final AuditRunParameters auditParameters =
        new AuditRunParameters(
            parseRequestedAt(parameters.getString("requestedAt")),
            defaultScope(parameters.getString("sourceScope")),
            Boolean.parseBoolean(parameters.getString("forceReprocess")));
    final UUID runId = runIdFor(jobExecution);
    repository.startRun(runId, auditParameters);
    jobExecution.getExecutionContext().putString(RUN_ID_CONTEXT_KEY, runId.toString());
  }

  private static UUID runIdFor(final JobExecution execution) {
    try {
      final Long instanceId = execution.getJobInstance().getId();
      if (instanceId == null) {
        throw new IllegalStateException("missing job instance id");
      }
      return UUID.nameUUIDFromBytes(
          ("documentIngestionJob:" + instanceId).getBytes(StandardCharsets.UTF_8));
    } catch (final RuntimeException exception) {
      throw new AuditPersistenceException("audit job instance is invalid");
    }
  }

  @Override
  public ExitStatus afterStep(final StepExecution stepExecution) {
    final UUID runId = runId(stepExecution.getJobExecution());
    final long durationMs = durationMs(stepExecution.getStartTime(), stepExecution.getEndTime());
    repository.updateStepAggregate(
        runId,
        new StepAggregate(
            AuditStep.fromBatchStepName(stepExecution.getStepName()),
            stepExecution.getReadCount(),
            stepExecution.getWriteCount(),
            stepExecution.getSkipCount(),
            durationMs));
    return stepExecution.getExitStatus();
  }

  @Override
  public void afterJob(final JobExecution jobExecution) {
    repository.finishRun(runId(jobExecution), mapStatus(jobExecution.getStatus()));
  }

  private static UUID runId(final JobExecution execution) {
    try {
      return UUID.fromString(execution.getExecutionContext().getString(RUN_ID_CONTEXT_KEY));
    } catch (final RuntimeException exception) {
      throw new AuditPersistenceException("audit run context is invalid");
    }
  }

  private static AuditRunStatus mapStatus(final BatchStatus status) {
    if (status == null) {
      return AuditRunStatus.UNKNOWN;
    }
    return switch (status) {
      case COMPLETED -> AuditRunStatus.COMPLETED;
      case FAILED -> AuditRunStatus.FAILED;
      case STOPPED -> AuditRunStatus.STOPPED;
      case ABANDONED -> AuditRunStatus.ABANDONED;
      default -> AuditRunStatus.UNKNOWN;
    };
  }

  private static Instant parseRequestedAt(final String value) {
    try {
      return value == null ? Instant.now() : Instant.parse(value);
    } catch (final DateTimeParseException exception) {
      throw new AuditPersistenceException("invalid audit request timestamp");
    }
  }

  private static String defaultScope(final String value) {
    return value == null || value.isBlank() ? "all" : value;
  }

  private static long durationMs(final LocalDateTime start, final LocalDateTime end) {
    if (start == null || end == null || end.isBefore(start)) {
      return 0;
    }
    return Duration.between(start.toInstant(ZoneOffset.UTC), end.toInstant(ZoneOffset.UTC))
        .toMillis();
  }
}
