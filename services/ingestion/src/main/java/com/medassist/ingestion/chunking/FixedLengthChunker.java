package com.medassist.ingestion.chunking;

import com.medassist.domain.Chunk;
import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.domain.TableBlock;
import com.medassist.ingestion.pipeline.mapping.SourceRangeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sentence-safe fixed-size baseline used by the M2.5 chunking ablation. */
public final class FixedLengthChunker implements Chunker {
  static final String STRATEGY_ID = "fixed-v1";
  private final TokenCounter tokenCounter;

  public FixedLengthChunker() {
    this(new SimpleTokenCounter());
  }

  public FixedLengthChunker(final TokenCounter tokenCounter) {
    this.tokenCounter = tokenCounter;
  }

  @Override
  public List<Chunk> chunk(
      final UUID documentVersionId,
      final String documentTitle,
      final DocumentIR ir,
      final ChunkingOptions options) {
    final List<Chunk> chunks = new ArrayList<>();
    for (final Section section : ir.sections()) {
      appendSection(
          chunks, documentVersionId, documentTitle, ir.metadata(), List.of(), section, options);
    }
    for (final TableBlock table : ir.tables()) {
      appendTable(chunks, documentVersionId, documentTitle, ir.metadata(), table, options);
    }
    return chunks;
  }

  private void appendSection(
      final List<Chunk> chunks,
      final UUID documentVersionId,
      final String documentTitle,
      final Map<String, String> metadata,
      final List<String> parents,
      final Section section,
      final ChunkingOptions options) {
    final String breadcrumb = ChunkingSupport.breadcrumb(documentTitle, parents, section.heading());
    if (!section.text().isBlank()) {
      final List<ChunkingSupport.TextSpan> sentences =
          ChunkingSupport.sentenceUnits(
              section.text(), section.sourceRange(), tokenCounter, options);
      for (final ChunkingSupport.TextSpan part :
          ChunkingSupport.groupUnits(sentences, tokenCounter, options)) {
        chunks.add(
            ChunkingSupport.createChunk(
                documentVersionId,
                chunks.size(),
                section.path(),
                part.text(),
                ChunkingSupport.mappedRange(
                    part, metadata, SourceRangeMap.sectionTextField(section.path())),
                breadcrumb,
                STRATEGY_ID,
                tokenCounter));
      }
    }
    final List<String> childParents = new ArrayList<>(parents);
    if (!section.heading().isBlank()) {
      childParents.add(section.heading());
    }
    for (final Section child : section.children()) {
      appendSection(
          chunks, documentVersionId, documentTitle, metadata, childParents, child, options);
    }
  }

  private void appendTable(
      final List<Chunk> chunks,
      final UUID documentVersionId,
      final String documentTitle,
      final Map<String, String> metadata,
      final TableBlock table,
      final ChunkingOptions options) {
    final String breadcrumb =
        ChunkingSupport.breadcrumb(documentTitle, List.of(), table.sectionPath());
    for (final String text : ChunkingSupport.tableTexts(table, tokenCounter, options)) {
      chunks.add(
          ChunkingSupport.createChunk(
              documentVersionId,
              chunks.size(),
              table.sectionPath(),
              text,
              ChunkingSupport.mappedFullRange(
                  text,
                  table.sourceRange(),
                  metadata,
                  SourceRangeMap.tableTextField(
                      table.sectionPath(), table.sourceRange().start(), table.sourceRange().end())),
              breadcrumb,
              STRATEGY_ID,
              tokenCounter));
    }
  }
}
