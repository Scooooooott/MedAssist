package com.medassist.retrieval.versioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class ChunkVersionDiffer {
  private static final int SUMMARY_LIMIT = 240;

  public List<ChunkDifference> diff(
      final List<VersionChunk> fromChunks, final List<VersionChunk> toChunks) {
    final Map<Integer, VersionChunk> from = byOrdinal(fromChunks);
    final Map<Integer, VersionChunk> to = byOrdinal(toChunks);
    final List<Integer> ordinals =
        java.util.stream.Stream.concat(from.keySet().stream(), to.keySet().stream())
            .distinct()
            .sorted()
            .toList();
    final List<ChunkDifference> differences = new ArrayList<>();
    for (final int ordinal : ordinals) {
      final VersionChunk before = from.get(ordinal);
      final VersionChunk after = to.get(ordinal);
      if (before == null) {
        differences.add(difference(after, "ADDED"));
      } else if (after == null) {
        differences.add(difference(before, "REMOVED"));
      } else if (!before.text().equals(after.text())
          || !before.sectionPath().equals(after.sectionPath())) {
        differences.add(difference(after, "CHANGED"));
      }
    }
    return List.copyOf(differences);
  }

  private Map<Integer, VersionChunk> byOrdinal(final List<VersionChunk> chunks) {
    return chunks.stream()
        .collect(Collectors.toUnmodifiableMap(VersionChunk::ordinal, Function.identity()));
  }

  private ChunkDifference difference(final VersionChunk chunk, final String changeType) {
    final String normalized = chunk.text().replaceAll("\\s+", " ").trim();
    final String summary =
        normalized.length() <= SUMMARY_LIMIT ? normalized : normalized.substring(0, SUMMARY_LIMIT);
    return new ChunkDifference(chunk.ordinal(), chunk.sectionPath(), changeType, summary);
  }
}
