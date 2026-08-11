package com.medassist.ingestion.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        chunker.chunk(UUID.randomUUID(), "Doc", ir, new ChunkingOptions(5, 8, 1, 3));

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
    assertEquals("structure-v1", chunks.get(0).metadata().get("chunking_strategy_id"));
    assertEquals("Table Doc > 2", chunks.get(0).metadata().get("breadcrumb"));
  }

  @Test
  void keepsSourceTextAndRangesExactWithoutBreadcrumbPrefix() {
    final String text = "First sentence. Second sentence.\n\nThird sentence.";
    final long sourceStart = 17;
    final DocumentIR ir =
        new DocumentIR(
            List.of(
                new Section(
                    "1",
                    "",
                    1,
                    text,
                    List.of(),
                    new SourceRange(sourceStart, sourceStart + text.length()))),
            List.of(),
            Map.of());

    final List<Chunk> chunks =
        chunker.chunk(UUID.randomUUID(), "Clinical Note", ir, new ChunkingOptions(4, 6, 1, 0));

    assertTrue(chunks.size() > 1);
    for (final Chunk chunk : chunks) {
      final int start = Math.toIntExact(chunk.sourceRange().start() - sourceStart);
      final int end = Math.toIntExact(chunk.sourceRange().end() - sourceStart);
      assertEquals(text.substring(start, end), chunk.text());
      assertFalse(chunk.text().startsWith("Clinical Note"));
      assertEquals("Clinical Note", chunk.metadata().get("breadcrumb"));
      assertEquals("structure-v1", chunk.metadata().get("chunking_strategy_id"));
      assertTrue(chunk.tokenCount() <= 6);
    }
  }

  @Test
  void handlesNoHeadingAndOversizeTableExplicitly() {
    final String value = "one two three four five six";
    final TableBlock table =
        new TableBlock(
            "1",
            "Findings",
            List.of("Name", "Value"),
            List.of(Map.of("Name", "A", "Value", value)),
            "",
            new SourceRange(0, value.length()));

    assertThrows(
        RuntimeException.class,
        () ->
            chunker.chunk(
                UUID.randomUUID(),
                "Doc",
                new DocumentIR(List.of(), List.of(table), Map.of()),
                new ChunkingOptions(3, 5, 1, 0)));
  }
}
