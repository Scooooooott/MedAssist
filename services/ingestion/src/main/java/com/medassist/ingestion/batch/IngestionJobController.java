package com.medassist.ingestion.batch;

import com.medassist.ingestion.batch.IngestionJobService.OperationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingestion/jobs")
public class IngestionJobController {
  private final IngestionJobService jobService;

  public IngestionJobController(final IngestionJobService jobService) {
    this.jobService = jobService;
  }

  @PostMapping("/document-ingestion")
  ResponseEntity<IngestionJobResponse> trigger(
      @RequestBody(required = false) final IngestionJobRequest request) {
    final IngestionJobRequest effectiveRequest =
        request == null ? new IngestionJobRequest(null, false) : request;
    return toResponse(
        jobService.startDocumentIngestion(
            effectiveRequest.sourceScope(), effectiveRequest.forceReprocess()));
  }

  @PostMapping("/context-backfill")
  ResponseEntity<IngestionJobResponse> triggerContextBackfill() {
    return toResponse(jobService.startContextBackfill());
  }

  @PostMapping("/document-ingestion/{executionId}/restart")
  ResponseEntity<IngestionJobResponse> restart(@PathVariable final long executionId) {
    return toResponse(jobService.restartDocumentIngestion(executionId));
  }

  private static ResponseEntity<IngestionJobResponse> toResponse(final OperationResult result) {
    final IngestionJobResponse body =
        new IngestionJobResponse(result.executionId(), result.status(), result.message());
    return switch (result.outcome()) {
      case ACCEPTED -> ResponseEntity.accepted().body(body);
      case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(body);
      case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
      case FAILURE -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    };
  }

  public record IngestionJobRequest(String sourceScope, boolean forceReprocess) {}

  public record IngestionJobResponse(Long executionId, String status, String message) {}
}
