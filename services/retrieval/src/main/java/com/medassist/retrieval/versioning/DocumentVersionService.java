package com.medassist.retrieval.versioning;

import com.medassist.retrieval.config.RetrievalProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class DocumentVersionService {
  private final DocumentVersionRepository repository;
  private final ChunkVersionDiffer differ;
  private final RetrievalProperties properties;
  private final Clock clock;

  @Autowired
  public DocumentVersionService(
      final DocumentVersionRepository repository,
      final ChunkVersionDiffer differ,
      final RetrievalProperties properties) {
    this(repository, differ, properties, Clock.systemDefaultZone());
  }

  DocumentVersionService(
      final DocumentVersionRepository repository,
      final ChunkVersionDiffer differ,
      final RetrievalProperties properties,
      final Clock clock) {
    this.repository = repository;
    this.differ = differ;
    this.properties = properties;
    this.clock = clock;
  }

  public List<DocumentVersionView> history(final UUID documentId) {
    final LocalDate staleBefore = LocalDate.now(clock).minusYears(properties.getStalenessYears());
    return repository.history(documentId).stream()
        .map(view -> withStaleness(view, staleBefore))
        .toList();
  }

  public VersionDiffResponse diff(
      final UUID documentId,
      final UUID from,
      final UUID to,
      final String requestedChunkingStrategyId) {
    final String chunkingStrategyId = normalizeStrategy(requestedChunkingStrategyId);
    return new VersionDiffResponse(
        documentId,
        from,
        to,
        differ.diff(
            repository.chunks(documentId, from, chunkingStrategyId),
            repository.chunks(documentId, to, chunkingStrategyId)));
  }

  private DocumentVersionView withStaleness(
      final DocumentVersionView view, final LocalDate staleBefore) {
    final Boolean stale =
        view.effectiveDate() == null ? null : view.effectiveDate().isBefore(staleBefore);
    return new DocumentVersionView(
        view.id(),
        view.documentId(),
        view.version(),
        view.effectiveDate(),
        view.status(),
        view.supersededBy(),
        view.publisher(),
        stale);
  }

  private String normalizeStrategy(final String requestedChunkingStrategyId) {
    return requestedChunkingStrategyId == null || requestedChunkingStrategyId.isBlank()
        ? properties.getDefaultChunkingStrategyId()
        : requestedChunkingStrategyId.trim();
  }
}
