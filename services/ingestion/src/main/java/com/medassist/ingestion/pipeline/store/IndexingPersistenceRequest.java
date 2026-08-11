package com.medassist.ingestion.pipeline.store;

import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import java.util.Map;
import java.util.Objects;

/** The only application input accepted by the indexing persistence boundary. */
public record IndexingPersistenceRequest(
    IndexingResult result,
    DocumentIdentity identity,
    DocumentVersionMetadata version,
    ContextualRetrievalMode contextualMode,
    String contextPromptVersion,
    Map<String, Integer> deidentificationPhiTypeCounts) {
  public IndexingPersistenceRequest {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(contextualMode, "contextualMode");
    contextPromptVersion = requireText(contextPromptVersion, "contextPromptVersion");
    deidentificationPhiTypeCounts =
        Map.copyOf(
            Objects.requireNonNull(deidentificationPhiTypeCounts, "deidentificationPhiTypeCounts"));
    if (deidentificationPhiTypeCounts.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || !entry.getKey().matches("[A-Z][A-Z0-9_]{0,63}")
                    || entry.getValue() == null
                    || entry.getValue() < 0)) {
      throw new IllegalArgumentException("de-identification PHI counts are invalid");
    }
    if (!identity.logicalDocumentId().equals(result.document().logicalDocumentId())) {
      throw new IllegalArgumentException("identity does not match indexing result");
    }
    if (!version.documentVersionId().equals(result.document().documentVersionId())) {
      throw new IllegalArgumentException("version does not match indexing result");
    }
    if (contextualMode == ContextualRetrievalMode.OFF
        && !result.chunks().stream().allMatch(chunk -> chunk.contextPrefix().isBlank())) {
      throw new IllegalArgumentException("OFF contextual mode cannot persist a context prefix");
    }
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
