package com.medassist.ingestion.batch.steps;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.domain.DocumentIR;
import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.batch.stage.QuarantineStage;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.discovery.ObjectDescriptor;
import com.medassist.ingestion.discovery.ObjectStoreCatalog;
import com.medassist.ingestion.pipeline.model.FailureStage;
import com.medassist.ingestion.pipeline.model.IngestionWorkItem;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import com.medassist.ingestion.pipeline.parse.ParseAndDeidentifyProcessor;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;

class ParseAndDeidentifyTaskletTest {
  private static final UUID RUN_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID VERSION_ID = UUID.randomUUID();

  @Test
  void reloadsDescriptorAndPersistsOnlyDeidentifiedOutput() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final ObjectDescriptor descriptor = descriptor();
    final ObjectStoreCatalog catalog = () -> List.of(descriptor);
    final ParseAndDeidentifyProcessor processor = mock(ParseAndDeidentifyProcessor.class);
    final DurableStageItem item = item();
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.DISCOVERED))
        .thenReturn(List.of(item));
    final DocumentIR deidentified = new DocumentIR(List.of(), List.of(), Map.of());
    when(processor.process(any(IngestionWorkItem.class)))
        .thenReturn(
            new ParseAndDeidentifyState(
                new IngestionWorkItem(
                    new com.medassist.ingestion.discovery.ObjectDiscoveryResult(
                        descriptor,
                        "hash",
                        java.util.Optional.empty(),
                        DiscoveryClassification.NEW,
                        true),
                    DOCUMENT_ID,
                    VERSION_ID),
                deidentified,
                Map.of("PERSON", 1),
                "policy-v1",
                List.of(),
                ProcessingStatus.SUCCEEDED,
                FailureStage.NONE,
                ""));

    new ParseAndDeidentifyTasklet(catalog, repository, processor)
        .execute(new StepContribution(stepExecution()), context());

    verify(processor).process(any(IngestionWorkItem.class));
    verify(repository)
        .saveDeidentified(
            RUN_ID,
            VERSION_ID,
            IngestionStageStatus.DISCOVERED,
            deidentified,
            Map.of("PERSON", 1),
            "policy-v1",
            ProcessingStatus.SUCCEEDED);
  }

  @Test
  void quarantinesBusinessFailureWithoutPersistingAnIr() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final ObjectDescriptor descriptor = descriptor();
    final ParseAndDeidentifyProcessor processor = mock(ParseAndDeidentifyProcessor.class);
    final DurableStageItem item = item();
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.DISCOVERED))
        .thenReturn(List.of(item));
    final IngestionWorkItem workItem =
        new IngestionWorkItem(
            new com.medassist.ingestion.discovery.ObjectDiscoveryResult(
                descriptor, "hash", java.util.Optional.empty(), DiscoveryClassification.NEW, true),
            DOCUMENT_ID,
            VERSION_ID);
    when(processor.process(any(IngestionWorkItem.class)))
        .thenReturn(
            new ParseAndDeidentifyState(
                workItem,
                null,
                Map.of(),
                "",
                List.of(),
                ProcessingStatus.QUARANTINED,
                FailureStage.DEIDENTIFICATION,
                "de-identification failure"));

    new ParseAndDeidentifyTasklet(() -> List.of(descriptor), repository, processor)
        .execute(new StepContribution(stepExecution()), context());

    verify(repository)
        .quarantine(
            RUN_ID,
            VERSION_ID,
            IngestionStageStatus.DISCOVERED,
            QuarantineStage.DEIDENTIFICATION,
            "DEIDENTIFICATION_FAILED",
            "de-identification failure");
  }

  private static DurableStageItem item() {
    return new DurableStageItem(
        RUN_ID,
        DOCUMENT_ID,
        VERSION_ID,
        URI.create("s3://bucket/doc"),
        "guidelines",
        "text/plain",
        3,
        "hash",
        null,
        DiscoveryClassification.NEW,
        Map.of(),
        false,
        IngestionStageStatus.DISCOVERED,
        null,
        Map.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ObjectDescriptor descriptor() {
    return new ObjectDescriptor(
        URI.create("s3://bucket/doc"),
        "guidelines",
        "text/plain",
        3,
        Map.of(),
        () -> new ByteArrayInputStream("doc".getBytes()));
  }

  private static StepExecution stepExecution() {
    return new StepExecution(
        "parse",
        new JobExecution(
            1L,
            new org.springframework.batch.core.job.JobInstance(1L, "job"),
            new org.springframework.batch.core.job.parameters.JobParametersBuilder()
                .toJobParameters()));
  }

  private static ChunkContext context() {
    final StepExecution execution = stepExecution();
    execution
        .getJobExecution()
        .getExecutionContext()
        .putString(IngestionStepContext.RUN_ID_CONTEXT_KEY, RUN_ID.toString());
    return new ChunkContext(new StepContext(execution));
  }
}
