package com.medassist.retrieval.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.repository.RankedChunk;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RrfFusionTest {
  private final RrfFusion fusion = new RrfFusion();

  @Test
  void fusesDuplicateChunksAndPreservesChannelProvenance() {
    final RetrievedChunk shared = chunk("00000000-0000-0000-0000-000000000001", 0.8);
    final RetrievedChunk vectorOnly = chunk("00000000-0000-0000-0000-000000000002", 0.7);

    final List<RetrievedChunk> result =
        fusion.fuse(
            List.of(new RankedChunk(shared, 1, 0.8), new RankedChunk(vectorOnly, 2, 0.7)),
            List.of(new RankedChunk(shared, 1, 0.9)),
            2,
            60,
            1.0,
            1.0);

    assertEquals(shared.chunkId(), result.get(0).chunkId());
    assertEquals(1, result.get(0).vectorRank());
    assertEquals(1, result.get(0).lexicalRank());
    assertNotNull(result.get(0).fusedScore());
    assertEquals("VECTOR+LEXICAL_RRF", result.get(0).retrievalMethod());
  }

  @Test
  void usesChunkIdAsDeterministicTieBreaker() {
    final RetrievedChunk first = chunk("00000000-0000-0000-0000-000000000001", 0.5);
    final RetrievedChunk second = chunk("00000000-0000-0000-0000-000000000002", 0.5);

    final List<RetrievedChunk> result =
        fusion.fuse(
            List.of(new RankedChunk(second, 1, 0.5)),
            List.of(new RankedChunk(first, 1, 0.5)),
            2,
            60,
            1.0,
            1.0);

    assertEquals(first.chunkId(), result.get(0).chunkId());
  }

  private RetrievedChunk chunk(final String id, final double score) {
    return new RetrievedChunk(
        UUID.fromString(id),
        UUID.fromString("10000000-0000-0000-0000-000000000001"),
        0,
        "1",
        "Evidence text.",
        2,
        0,
        14,
        score,
        "TEST",
        "COSINE",
        "GUIDELINE",
        "Publisher",
        "Title",
        "v1",
        LocalDate.of(2026, 1, 1),
        Map.of());
  }
}
