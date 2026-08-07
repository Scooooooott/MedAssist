package com.medassist.ingestion.batch;

import java.time.Instant;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingestion/jobs")
public class IngestionJobController {
  private final JobLauncher jobLauncher;
  private final Job documentIngestionJob;

  public IngestionJobController(final JobLauncher jobLauncher, final Job documentIngestionJob) {
    this.jobLauncher = jobLauncher;
    this.documentIngestionJob = documentIngestionJob;
  }

  @PostMapping("/document-ingestion")
  ResponseEntity<IngestionJobResponse> trigger(
      @RequestBody(required = false) final IngestionJobRequest request) throws Exception {
    final IngestionJobRequest effectiveRequest =
        request == null ? new IngestionJobRequest(null, false) : request;
    final JobParameters parameters =
        new JobParametersBuilder()
            .addString("requestedAt", Instant.now().toString())
            .addString(
                "sourceScope",
                effectiveRequest.sourceScope() == null ? "all" : effectiveRequest.sourceScope())
            .addString("forceReprocess", Boolean.toString(effectiveRequest.forceReprocess()))
            .toJobParameters();
    try {
      final JobExecution execution = jobLauncher.run(documentIngestionJob, parameters);
      return ResponseEntity.accepted()
          .body(
              new IngestionJobResponse(execution.getId(), execution.getStatus().name(), "started"));
    } catch (final IllegalStateException exception) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new IngestionJobResponse(null, "ALREADY_RUNNING", exception.getMessage()));
    }
  }

  public record IngestionJobRequest(String sourceScope, boolean forceReprocess) {}

  public record IngestionJobResponse(Long executionId, String status, String message) {}
}
