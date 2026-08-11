package com.medassist.ingestion.batch.steps;

import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepExecution;

/** Shared, content-free values carried by the Spring Batch job execution. */
public final class IngestionStepContext {
  public static final String RUN_ID_CONTEXT_KEY = "medassist.ingestion.run-id";

  private IngestionStepContext() {}

  public static UUID runId(final ChunkContext chunkContext) {
    if (chunkContext == null || chunkContext.getStepContext() == null) {
      throw new IllegalStateException("ingestion step context is missing");
    }
    final StepExecution stepExecution = chunkContext.getStepContext().getStepExecution();
    if (stepExecution == null) {
      throw new IllegalStateException("ingestion step execution is missing");
    }
    try {
      return UUID.fromString(
          stepExecution.getJobExecution().getExecutionContext().getString(RUN_ID_CONTEXT_KEY));
    } catch (final RuntimeException exception) {
      throw new IllegalStateException("ingestion run context is invalid", exception);
    }
  }
}
