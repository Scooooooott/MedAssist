package com.medassist.ingestion.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.domain.Chunk;
import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.domain.SourceRange;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlternativeChunkerTest {
  private static final String TEXT =
      "Alpha sentence. Beta sentence. Gamma sentence. Delta sentence.";

  @Test
  void fixedLengthNeverSplitsSentenceOrExceedsMaximum() {
    final List<Chunk> chunks =
        new FixedLengthChunker()
            .chunk(UUID.randomUUID(), "Doc", document(), new ChunkingOptions(5, 6, 1, 0));

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 6));
    assertTrue(chunks.stream().allMatch(chunk -> chunk.text().endsWith(".")));
    assertTrue(
        chunks.stream()
            .allMatch(chunk -> "fixed-v1".equals(chunk.metadata().get("chunking_strategy_id"))));
  }

  @Test
  void semanticChunkerBreaksOnLowCosineBetweenSentences() {
    final SentenceEmbeddingProvider provider =
        sentence -> {
          if (sentence.startsWith("Gamma") || sentence.startsWith("Delta")) {
            return new double[] {0.0, 1.0};
          }
          return new double[] {1.0, 0.0};
        };

    final List<Chunk> chunks =
        new SemanticChunker(provider, 0.5)
            .chunk(UUID.randomUUID(), "Doc", document(), new ChunkingOptions(100, 100, 1, 0));

    assertEquals(2, chunks.size());
    assertEquals("Alpha sentence. Beta sentence.", chunks.get(0).text());
    assertEquals("Gamma sentence. Delta sentence.", chunks.get(1).text());
    assertTrue(
        chunks.stream()
            .allMatch(chunk -> "semantic-v1".equals(chunk.metadata().get("chunking_strategy_id"))));
  }

  private DocumentIR document() {
    return new DocumentIR(
        List.of(new Section("1", "Heading", 1, TEXT, List.of(), new SourceRange(0, TEXT.length()))),
        List.of(),
        Map.of());
  }
}
