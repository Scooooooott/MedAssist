package com.medassist.ingestion.chunking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.domain.Chunk;
import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.domain.SourceRange;
import com.medassist.domain.TableBlock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructureAwareChunkerTest {
  private final StructureAwareChunker chunker = new StructureAwareChunker();

  @Test
  void keepsSoapSectionsSeparate() {
    final DocumentIR ir =
        new DocumentIR(
            List.of(
                new Section(
                    "S",
                    "Subjective",
                    1,
                    "Patient reports cough.",
                    List.of(),
                    new SourceRange(0, 22)),
                new Section(
                    "O", "Objective", 1, "Vitals are stable.", List.of(), new SourceRange(23, 41))),
            List.of(),
            Map.of());

    final List<Chunk> chunks =
        chunker.chunk(UUID.randomUUID(), "SOAP Note", ir, ChunkingOptions.defaults());

    assertTrue(chunks.stream().anyMatch(chunk -> chunk.sectionPath().equals("S")));
    assertTrue(chunks.stream().anyMatch(chunk -> chunk.sectionPath().equals("O")));
  }

  @Test
  void splitsLongSectionsOnSentenceBoundaries() {
    final String text =
        "First complete sentence. Second complete sentence. Third complete sentence.";
    final DocumentIR ir =
        new DocumentIR(
            List.of(
                new Section("1", "Long", 1, text, List.of(), new SourceRange(0, text.length()))),
            List.of(),
            Map.of());

    final List<Chunk> chunks =
        chunker.chunk(UUID.randomUUID(), "Doc", ir, new ChunkingOptions(8, 8, 1, 0));

    assertTrue(chunks.size() > 1);
    assertTrue(
        chunks.stream()
            .allMatch(
                chunk ->
                    chunk.text().endsWith(".") || chunk.text().contains(System.lineSeparator())));
  }

  @Test
  void keepsSentenceOverlapWithoutBreakingTheHardLimit() {
    final String text = "Alpha sentence. Beta sentence. Gamma sentence. Delta sentence.";
    final DocumentIR ir =
        new DocumentIR(
            List.of(
                new Section("1", "Long", 1, text, List.of(), new SourceRange(0, text.length()))),
            List.of(),
            Map.of());

    final List<Chunk> chunks =
        chunker.chunk(UUID.randomUUID(), "Doc", ir, new ChunkingOptions(5, 8, 1, 2));

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 8));
    assertTrue(chunks.get(0).text().contains("Alpha sentence."));
    assertTrue(chunks.get(1).text().contains("Beta sentence."));
  }

  @Test
  void tableBecomesIndependentChunk() {
    final TableBlock table =
        new TableBlock(
            "2",
            "Findings",
            List.of("Name", "Value"),
            List.of(Map.of("Name", "A", "Value", "B")),
            "",
            new SourceRange(10, 30));
    final DocumentIR ir = new DocumentIR(List.of(), List.of(table), Map.of());

    final List<Chunk> chunks =
        chunker.chunk(UUID.randomUUID(), "Table Doc", ir, ChunkingOptions.defaults());

    assertFalse(chunks.isEmpty());
    assertTrue(chunks.get(0).text().contains("| Name | Value |"));
  }
}
