package com.medassist.agent.execution;

import com.medassist.agent.state.ChunkCandidateMetadata;
import java.util.List;
import java.util.Objects;

/** Projects an untrusted backend response into metadata-only agent results. */
public final class ToolResultProjector {
  private ToolResultProjector() {}

  public static SafeToolResultProjection project(final ToolBackendResult result) {
    Objects.requireNonNull(result, "result");
    final List<SafeChunkProjection> chunks =
        result.chunks().stream()
            .map(
                chunk ->
                    new SafeChunkProjection(
                        chunk.chunkId(), chunk.version(), chunk.source(), chunk.citationLocator()))
            .toList();
    return new SafeToolResultProjection(chunks, result.aggregationColumns());
  }

  public static List<ChunkCandidateMetadata> candidateMetadata(final ToolBackendResult result) {
    Objects.requireNonNull(result, "result");
    return result.chunks().stream()
        .map(
            chunk ->
                new ChunkCandidateMetadata(
                    chunk.chunkId(),
                    chunk.rangeStart(),
                    chunk.rangeEnd(),
                    chunk.chunkHash(),
                    chunk.score(),
                    chunk.rank()))
        .toList();
  }

  public static RuntimeSafetyEvidence runtimeSafetyEvidence(final ToolBackendResult result) {
    Objects.requireNonNull(result, "result");
    return new RuntimeSafetyEvidence(
        result.chunks().stream()
            .map(chunk -> new RuntimeEvidenceChunk(chunk.chunkId(), chunk.content()))
            .toList());
  }
}
