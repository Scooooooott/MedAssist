package com.medassist.ingestion.batch;

import java.time.Instant;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class IngestionJobService {
  private static final String JOB_NAME = "documentIngestionJob";
  private static final String ALREADY_RUNNING_MESSAGE = "An ingestion job is already running.";
  private static final String START_FAILURE_MESSAGE = "Unable to start ingestion job.";
  private static final String EXECUTION_NOT_FOUND_MESSAGE =
      "Ingestion job execution was not found.";
  private static final String NOT_RESTARTABLE_MESSAGE =
      "Only failed or stopped ingestion jobs can be restarted.";

  private final JobOperator jobOperator;
  private final JobRepository jobRepository;
  private final Job documentIngestionJob;
  private final Job contextBackfillJob;
  private final IngestionJobMutex jobMutex;

  public IngestionJobService(
      final JobOperator jobOperator,
      final JobRepository jobRepository,
      @Qualifier("documentIngestionJob") final Job documentIngestionJob,
      @Qualifier("contextBackfillJob") final Job contextBackfillJob,
      final IngestionJobMutex jobMutex) {
    this.jobOperator = jobOperator;
    this.jobRepository = jobRepository;
    this.documentIngestionJob = documentIngestionJob;
    this.contextBackfillJob = contextBackfillJob;
    this.jobMutex = jobMutex;
  }

  public OperationResult startDocumentIngestion(
      final String sourceScope, final boolean forceReprocess) {
    final JobParameters parameters =
        new JobParametersBuilder()
            .addString("requestedAt", Instant.now().toString())
            .addString("sourceScope", normalizeSourceScope(sourceScope))
            .addString("forceReprocess", Boolean.toString(forceReprocess))
            .toJobParameters();
    return start(documentIngestionJob, parameters);
  }

  public OperationResult startContextBackfill() {
    final JobParameters parameters =
        new JobParametersBuilder()
            .addString("requestedAt", Instant.now().toString())
            .toJobParameters();
    return start(contextBackfillJob, parameters);
  }

  public OperationResult restartDocumentIngestion(final long executionId) {
    final JobExecution previousExecution = jobRepository.getJobExecution(executionId);
    if (previousExecution == null
        || previousExecution.getJobInstance() == null
        || !JOB_NAME.equals(previousExecution.getJobInstance().getJobName())) {
      return new OperationResult(
          null, "EXECUTION_NOT_FOUND", EXECUTION_NOT_FOUND_MESSAGE, Outcome.NOT_FOUND);
    }
    if (!isRestartable(previousExecution.getStatus())) {
      return new OperationResult(
          executionId, "NOT_RESTARTABLE", NOT_RESTARTABLE_MESSAGE, Outcome.CONFLICT);
    }
    if (!jobMutex.tryAcquire()) {
      return alreadyRunning();
    }

    try {
      final JobExecution restartedExecution = jobOperator.restart(previousExecution);
      return new OperationResult(
          restartedExecution.getId(),
          restartedExecution.getStatus().name(),
          "restarted",
          Outcome.ACCEPTED);
    } catch (final Exception exception) {
      jobMutex.release();
      return startFailed();
    }
  }

  private OperationResult start(final Job job, final JobParameters parameters) {
    if (!jobMutex.tryAcquire()) {
      return alreadyRunning();
    }
    try {
      final JobExecution execution = jobOperator.start(job, parameters);
      return new OperationResult(
          execution.getId(), execution.getStatus().name(), "started", Outcome.ACCEPTED);
    } catch (final Exception exception) {
      jobMutex.release();
      return startFailed();
    }
  }

  private static OperationResult alreadyRunning() {
    return new OperationResult(null, "ALREADY_RUNNING", ALREADY_RUNNING_MESSAGE, Outcome.CONFLICT);
  }

  private static OperationResult startFailed() {
    return new OperationResult(null, "START_FAILED", START_FAILURE_MESSAGE, Outcome.FAILURE);
  }

  private static boolean isRestartable(final BatchStatus status) {
    return status == BatchStatus.FAILED || status == BatchStatus.STOPPED;
  }

  private static String normalizeSourceScope(final String sourceScope) {
    return sourceScope == null || sourceScope.isBlank() ? "all" : sourceScope.trim();
  }

  public enum Outcome {
    ACCEPTED,
    CONFLICT,
    NOT_FOUND,
    FAILURE
  }

  public record OperationResult(Long executionId, String status, String message, Outcome outcome) {}
}
