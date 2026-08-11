package com.medassist.ingestion.batch.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.medassist.ingestion.batch.stage.DiscoveredStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.discovery.DocumentFingerprintRepository;
import com.medassist.ingestion.discovery.ObjectDescriptor;
import com.medassist.ingestion.discovery.ObjectDiscoveryService;
import com.medassist.ingestion.discovery.ObjectStoreCatalog;
import com.medassist.ingestion.discovery.Sha256Hasher;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;

class DiscoverObjectsTaskletTest {
  private static final UUID RUN_ID = UUID.randomUUID();

  @Test
  void filtersScopeAndPersistsDeterministicSafeMetadata() throws Exception {
    final ObjectDescriptor included = object("guidelines", "s3://bucket/a", "a");
    final ObjectDescriptor excluded = object("other", "s3://bucket/b", "b");
    final ObjectStoreCatalog catalog = () -> List.of(excluded, included);
    final DocumentFingerprintRepository fingerprints = (source, uri) -> Optional.empty();
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final DiscoverObjectsTasklet tasklet =
        new DiscoverObjectsTasklet(
            new ObjectDiscoveryService(catalog, fingerprints, new Sha256Hasher()), repository);

    tasklet.execute(
        new StepContribution(stepExecution("guidelines", false)), context("guidelines", false));

    final var captor = org.mockito.ArgumentCaptor.forClass(DiscoveredStageItem.class);
    verify(repository).upsertDiscovered(captor.capture());
    final DiscoveredStageItem saved = captor.getValue();
    assertThat(saved.ingestionRunId()).isEqualTo(RUN_ID);
    assertThat(saved.sourceId()).isEqualTo("guidelines");
    assertThat(saved.safeObjectMetadata()).containsOnlyKeys("etag");
    assertThat(saved.safeObjectMetadata()).containsEntry("etag", "safe-etag");

    final DurableStageRepository secondRepository = mock(DurableStageRepository.class);
    final DiscoverObjectsTasklet secondTasklet =
        new DiscoverObjectsTasklet(
            new ObjectDiscoveryService(catalog, fingerprints, new Sha256Hasher()),
            secondRepository);
    secondTasklet.execute(
        new StepContribution(stepExecution("guidelines", false)), context("guidelines", false));
    final var secondCaptor = org.mockito.ArgumentCaptor.forClass(DiscoveredStageItem.class);
    verify(secondRepository).upsertDiscovered(secondCaptor.capture());
    assertThat(secondCaptor.getValue().logicalDocumentId()).isEqualTo(saved.logicalDocumentId());
    assertThat(secondCaptor.getValue().documentVersionId()).isEqualTo(saved.documentVersionId());
  }

  private static ObjectDescriptor object(
      final String sourceId, final String uri, final String content) {
    return new ObjectDescriptor(
        URI.create(uri),
        sourceId,
        "text/plain",
        content.length(),
        Map.of("etag", "safe-etag", "patientName", "must-not-persist"),
        () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
  }

  private static StepExecution stepExecution(
      final String sourceScope, final boolean forceReprocess) {
    return new StepExecution(
        "discover",
        new JobExecution(
            1L,
            new JobInstance(1L, "job"),
            new JobParametersBuilder()
                .addString("sourceScope", sourceScope)
                .addString("forceReprocess", Boolean.toString(forceReprocess))
                .toJobParameters()));
  }

  private static ChunkContext context(final String sourceScope, final boolean forceReprocess) {
    final StepExecution execution = stepExecution(sourceScope, forceReprocess);
    execution
        .getJobExecution()
        .getExecutionContext()
        .putString(IngestionStepContext.RUN_ID_CONTEXT_KEY, RUN_ID.toString());
    return new ChunkContext(new StepContext(execution));
  }
}
