package com.medassist.ingestion.batch.steps.store;

import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.store.DocumentIdentity;
import com.medassist.ingestion.pipeline.store.DocumentVersionMetadata;
import com.medassist.ingestion.pipeline.store.IndexingPersistenceRequest;
import com.medassist.ingestion.versioning.DocumentVersionMetadataExtractor;
import com.medassist.ingestion.versioning.VersionChainStatus;
import com.medassist.ingestion.versioning.VersionMetadataResult;
import com.medassist.ingestion.versioning.VersionMetadataStatus;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds a safe persistence request without performing persistence or version-chain work. */
public final class DefaultIndexingPersistenceRequestFactory
    implements IndexingPersistenceRequestFactory {
  private static final String UNKNOWN = "Unknown";
  private static final Set<String> STRUCTURAL_METADATA_KEYS =
      Set.of("title", "doc_type", "content_domain", "source_system");

  private final DocumentVersionMetadataExtractor metadataExtractor;
  private final ContextualRetrievalMode contextualMode;
  private final String contextPromptVersion;
  private final Clock clock;
  private final String defaultSourceSystem;
  private final String defaultDocType;
  private final String defaultContentDomain;

  public DefaultIndexingPersistenceRequestFactory(
      final DocumentVersionMetadataExtractor metadataExtractor,
      final ContextualRetrievalMode contextualMode,
      final String contextPromptVersion,
      final Clock clock,
      final String defaultSourceSystem,
      final String defaultDocType,
      final String defaultContentDomain) {
    this.metadataExtractor = Objects.requireNonNull(metadataExtractor, "metadataExtractor");
    this.contextualMode = Objects.requireNonNull(contextualMode, "contextualMode");
    this.contextPromptVersion = requireText(contextPromptVersion, "contextPromptVersion");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.defaultSourceSystem = requireText(defaultSourceSystem, "defaultSourceSystem");
    this.defaultDocType = requireText(defaultDocType, "defaultDocType");
    this.defaultContentDomain = requireText(defaultContentDomain, "defaultContentDomain");
  }

  @Override
  public IndexingPersistenceRequest create(final DurableStageItem item) {
    if (item == null
        || item.indexingResult() == null
        || item.deidentifiedIr() == null
        || item.logicalDocumentId() == null
        || item.documentVersionId() == null
        || item.sourceUri() == null
        || isBlank(item.contentHash())) {
      throw invalidRequest();
    }

    final IndexingResult result = item.indexingResult();
    if (!item.logicalDocumentId().equals(result.document().logicalDocumentId())
        || !item.documentVersionId().equals(result.document().documentVersionId())) {
      throw invalidRequest();
    }

    final VersionMetadataResult extracted = metadataExtractor.extract(item.deidentifiedIr());
    final boolean confirmed = extracted.status() == VersionMetadataStatus.CONFIRMED;
    final String publisher = confirmed ? extracted.publisher() : UNKNOWN;
    final String version = confirmed ? extracted.version() : UNKNOWN;
    final VersionChainStatus status =
        confirmed ? VersionChainStatus.ACTIVE : VersionChainStatus.UNKNOWN;

    final Map<String, String> safeMetadata = new LinkedHashMap<>();
    for (final String key : STRUCTURAL_METADATA_KEYS) {
      final String value = item.deidentifiedIr().metadata().get(key);
      if (value != null && !value.isBlank()) {
        safeMetadata.put(key, value);
      }
    }
    safeMetadata.put("title", result.document().title());
    if (isBlank(item.policyVersion())) {
      throw invalidRequest();
    }
    safeMetadata.put("policy_version", item.policyVersion());
    if (!confirmed) {
      final List<String> issueFields =
          extracted.issueFields().stream().map(field -> field.metadataKey()).sorted().toList();
      safeMetadata.put("version_metadata_review_fields", String.join(",", issueFields));
    }

    final String sourceSystem =
        safeValue(item.deidentifiedIr().metadata(), "source_system", defaultSourceSystem);
    final String docType = safeValue(item.deidentifiedIr().metadata(), "doc_type", defaultDocType);
    final String contentDomain =
        safeValue(item.deidentifiedIr().metadata(), "content_domain", defaultContentDomain);

    final DocumentIdentity identity =
        new DocumentIdentity(
            item.logicalDocumentId(),
            sourceSystem,
            item.sourceUri().toString(),
            docType,
            publisher,
            result.document().title());
    final DocumentVersionMetadata versionMetadata =
        new DocumentVersionMetadata(
            item.documentVersionId(),
            version,
            item.contentHash(),
            confirmed ? extracted.effectiveDate() : null,
            clock.instant(),
            status,
            null,
            item.sourceUri().toString(),
            contentDomain,
            safeMetadata);
    return new IndexingPersistenceRequest(
        result,
        identity,
        versionMetadata,
        contextualMode,
        contextPromptVersion,
        item.phiTypeCounts() == null ? Map.of() : item.phiTypeCounts());
  }

  private static String safeValue(
      final Map<String, String> metadata, final String key, final String defaultValue) {
    final String value = metadata.get(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static IndexingPersistenceRequestFactoryException invalidRequest() {
    return new IndexingPersistenceRequestFactoryException(
        IndexingPersistenceRequestFactoryException.Failure.INVALID_REQUEST);
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
