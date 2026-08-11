package com.medassist.retrieval.versioning;

import java.util.List;
import java.util.UUID;

public record VersionDiffResponse(
    UUID documentId, UUID fromVersionId, UUID toVersionId, List<ChunkDifference> differences) {
  public VersionDiffResponse {
    differences = List.copyOf(differences);
  }
}
