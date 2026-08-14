package com.medassist.retrieval.versioning;

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
  private final DocumentVersionService service;

  public DocumentVersionController(final DocumentVersionService service) {
    this.service = service;
  }

  @GetMapping
  public List<DocumentVersionView> history(@PathVariable final UUID documentId) {
    return service.history(documentId);
  }

  @GetMapping("/diff")
  public VersionDiffResponse diff(
      @PathVariable final UUID documentId,
      @RequestParam final UUID from,
      @RequestParam final UUID to,
      @RequestParam(required = false) final String chunkingStrategyId) {
    return service.diff(documentId, from, to, chunkingStrategyId);
  }
}
