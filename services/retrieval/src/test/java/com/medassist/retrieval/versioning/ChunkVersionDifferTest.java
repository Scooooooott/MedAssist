package com.medassist.retrieval.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkVersionDifferTest {
  private final ChunkVersionDiffer differ = new ChunkVersionDiffer();

  @Test
  void reportsAddedRemovedAndChangedChunksInOrdinalOrder() {
    final List<ChunkDifference> result =
        differ.diff(
            List.of(chunk(0, "old"), chunk(1, "removed"), chunk(3, "same")),
            List.of(chunk(0, "new"), chunk(2, "added"), chunk(3, "same")));

    assertEquals(List.of(0, 1, 2), result.stream().map(ChunkDifference::ordinal).toList());
    assertEquals(
        List.of("CHANGED", "REMOVED", "ADDED"),
        result.stream().map(ChunkDifference::changeType).toList());
  }

  @Test
  void emitsNoDifferenceForIdenticalVersions() {
    final List<VersionChunk> chunks = List.of(chunk(0, "same"));
    assertEquals(List.of(), differ.diff(chunks, chunks));
  }

  private VersionChunk chunk(final int ordinal, final String text) {
    return new VersionChunk(UUID.randomUUID(), ordinal, "section", text);
  }
}
