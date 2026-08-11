package com.medassist.retrieval.versioning;

import com.medassist.retrieval.config.RetrievalProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/versions")
public final class DocumentVersionController {
  private final DocumentVersionRepository repository;
  private final ChunkVersionDiffer differ;
  private final RetrievalProperties properties;

  public DocumentVersionController(
      final DocumentVersionRepository repository,
      final ChunkVersionDiffer differ,
      final RetrievalProperties properties) {
    this.repository = repository;
    this.differ = differ;
    this.properties = properties;
  }

  @GetMapping
  public List<DocumentVersionView> history(@PathVariable final UUID documentId) {
    return repository.history(documentId, properties.getStalenessYears());
  }

  @GetMapping("/diff")
  public VersionDiffResponse diff(
      @PathVariable final UUID documentId,
      @RequestParam final UUID from,
      @RequestParam final UUID to,
      @RequestParam(required = false) final String chunkingStrategyId) {
    final String effectiveStrategy =
        chunkingStrategyId == null || chunkingStrategyId.isBlank()
            ? properties.getDefaultChunkingStrategyId()
            : chunkingStrategyId.trim();
    return new VersionDiffResponse(
        documentId,
        from,
        to,
        differ.diff(
            repository.chunks(documentId, from, effectiveStrategy),
            repository.chunks(documentId, to, effectiveStrategy)));
  }
}
