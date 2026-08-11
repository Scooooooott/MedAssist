package com.medassist.ingestion.batch.steps.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.domain.DocumentIR;
import com.medassist.domain.SourceRange;
import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.pipeline.index.IndexableChunk;
import com.medassist.ingestion.pipeline.index.IndexableDocument;
import com.medassist.ingestion.pipeline.index.IndexableEmbedding;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import com.medassist.ingestion.versioning.DocumentVersionMetadataExtractor;
import com.medassist.ingestion.versioning.VersionChainStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultIndexingPersistenceRequestFactoryTest {
  private static final UUID LOGICAL_ID = UUID.randomUUID();
  private static final UUID VERSION_ID = UUID.randomUUID();
  private static final URI SOURCE_URI = URI.create("s3://safe-bucket/guide.pdf");
  private static final Instant NOW = Instant.parse("2026-08-07T10:15:30Z");
  private final DefaultIndexingPersistenceRequestFactory factory = factory();

  @Test
  void confirmedMetadataBuildsActiveRequestFromSafeFields() {
    final DurableStageItem item =
        item(
            new DocumentIR(
                List.of(),
                List.of(),
                Map.of(
                    "publisher", "Verified Publisher",
                    "version", "2026.1",
                    "effective_date", "2026-01-15",
                    "source_system", "registry",
                    "doc_type", "guideline",
                    "content_domain", "cardiology",
                    "title", "Safe object title",
                    "raw_text", "must never be copied")),
            Map.of("etag", "safe-etag"));

    final var request = factory.create(item);

    assertThat(request.identity().logicalDocumentId()).isEqualTo(LOGICAL_ID);
    assertThat(request.identity().sourceSystem()).isEqualTo("registry");
    assertThat(request.identity().sourceUri()).isEqualTo(SOURCE_URI.toString());
    assertThat(request.identity().docType()).isEqualTo("guideline");
    assertThat(request.identity().publisher()).isEqualTo("Verified Publisher");
    assertThat(request.identity().title()).isEqualTo("Safe indexed title");
    assertThat(request.version().status()).isEqualTo(VersionChainStatus.ACTIVE);
    assertThat(request.version().version()).isEqualTo("2026.1");
    assertThat(request.version().effectiveDate()).hasToString("2026-01-15");
    assertThat(request.version().retrievedAt()).isEqualTo(NOW);
    assertThat(request.version().supersededBy()).isNull();
    assertThat(request.version().storageUri()).isEqualTo(SOURCE_URI.toString());
    assertThat(request.version().metadata())
        .containsEntry("content_domain", "cardiology")
        .containsEntry("policy_version", "policy-v1")
        .containsEntry("title", "Safe indexed title")
        .doesNotContainKey("raw_text");
  }

  @Test
  void unknownMetadataDoesNotGuessAndRecordsOnlySortedIssueNames() {
    final String sensitiveValue = "secret-publisher-value";
    final DurableStageItem item =
        item(
            new DocumentIR(
                List.of(),
                List.of(),
                Map.of("publisher", sensitiveValue, "effective_date", "not-a-date")),
            Map.of());

    final var request = factory.create(item);

    assertThat(request.identity().publisher()).isEqualTo("Unknown");
    assertThat(request.version().version()).isEqualTo("Unknown");
    assertThat(request.version().effectiveDate()).isNull();
    assertThat(request.version().status()).isEqualTo(VersionChainStatus.UNKNOWN);
    assertThat(request.version().metadata().get("version_metadata_review_fields"))
        .isEqualTo("effective_date,version");
    assertThat(request.version().metadata().values()).doesNotContain(sensitiveValue);
  }

  @Test
  void missingExplicitSafeFieldsUseConfiguredDefaults() {
    final var request = factory.create(item(confirmedIr(), Map.of()));

    assertThat(request.identity().sourceSystem()).isEqualTo("default-source");
    assertThat(request.identity().docType()).isEqualTo("default-doc");
    assertThat(request.version().contentDomain()).isEqualTo("default-domain");
  }

  @Test
  void inconsistentIdsAndMissingPayloadsFailWithSafeException() {
    final DurableStageItem item = item(confirmedIr(), Map.of());
    final DurableStageItem inconsistent =
        new DurableStageItem(
            item.ingestionRunId(),
            UUID.randomUUID(),
            item.documentVersionId(),
            item.sourceUri(),
            item.sourceId(),
            item.mimeType(),
            item.sizeBytes(),
            "sensitive-hash",
            item.previousContentHash(),
            item.classification(),
            item.safeObjectMetadata(),
            item.forceReprocess(),
            item.status(),
            item.deidentifiedIr(),
            item.phiTypeCounts(),
            item.policyVersion(),
            item.processingStatus(),
            item.indexingResult(),
            item.quarantineStage(),
            item.errorCode(),
            item.safeReason());

    assertSafeFailure(inconsistent, "sensitive-hash");
    assertSafeFailure(null, "sensitive-hash");
    assertSafeFailure(itemWithoutIndexingResult(item), "sensitive-hash");
  }

  private static void assertSafeFailure(final DurableStageItem item, final String forbidden) {
    assertThatThrownBy(() -> factory().create(item))
        .isInstanceOf(IndexingPersistenceRequestFactoryException.class)
        .hasMessage("indexing request validation failed")
        .hasMessageNotContaining(forbidden);
  }

  private static DurableStageItem itemWithoutIndexingResult(final DurableStageItem item) {
    return new DurableStageItem(
        item.ingestionRunId(),
        item.logicalDocumentId(),
        item.documentVersionId(),
        item.sourceUri(),
        item.sourceId(),
        item.mimeType(),
        item.sizeBytes(),
        item.contentHash(),
        item.previousContentHash(),
        item.classification(),
        item.safeObjectMetadata(),
        item.forceReprocess(),
        item.status(),
        item.deidentifiedIr(),
        item.phiTypeCounts(),
        item.policyVersion(),
        item.processingStatus(),
        null,
        item.quarantineStage(),
        item.errorCode(),
        item.safeReason());
  }

  private static DefaultIndexingPersistenceRequestFactory factory() {
    return new DefaultIndexingPersistenceRequestFactory(
        new DocumentVersionMetadataExtractor(),
        ContextualRetrievalMode.RULE_BASED,
        "context-v1",
        Clock.fixed(NOW, ZoneOffset.UTC),
        "default-source",
        "default-doc",
        "default-domain");
  }

  private static DocumentIR confirmedIr() {
    return new DocumentIR(
        List.of(),
        List.of(),
        Map.of("publisher", "Verified Publisher", "version", "v1", "effective_date", "2026-01-15"));
  }

  private static DurableStageItem item(
      final DocumentIR deidentifiedIr, final Map<String, String> safeObjectMetadata) {
    final IndexingResult result =
        new IndexingResult(
            new IndexableDocument(
                LOGICAL_ID,
                VERSION_ID,
                "source-id",
                "Safe indexed title",
                "Safe result publisher",
                "policy-v1",
                Map.of()),
            List.of(
                new IndexableChunk(
                    UUID.randomUUID(),
                    VERSION_ID,
                    0,
                    "heading",
                    "safe text",
                    2,
                    new SourceRange(0, 9),
                    "heading",
                    "structure-v1",
                    PhiScanStatus.CLEAN,
                    Set.of(),
                    "safe context")),
            List.of(new IndexableEmbedding(UUID.randomUUID(), "model", "v1", 1, List.of(1.0f))));
    return new DurableStageItem(
        UUID.randomUUID(),
        LOGICAL_ID,
        VERSION_ID,
        SOURCE_URI,
        "source-id",
        "application/pdf",
        42,
        "safe-hash",
        null,
        DiscoveryClassification.NEW,
        safeObjectMetadata,
        false,
        IngestionStageStatus.INDEX_READY,
        deidentifiedIr,
        Map.of(),
        "policy-v1",
        ProcessingStatus.SUCCEEDED,
        result,
        null,
        null,
        null);
  }
}
