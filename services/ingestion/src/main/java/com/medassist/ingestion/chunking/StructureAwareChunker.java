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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StructureAwareChunker implements Chunker {
  static final String STRATEGY_ID = "structure-v1";
  private static final Logger LOGGER = LoggerFactory.getLogger(StructureAwareChunker.class);
  private final TokenCounter tokenCounter;

  public StructureAwareChunker() {
    this(new SimpleTokenCounter());
  }

  public StructureAwareChunker(final TokenCounter tokenCounter) {
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
      appendSectionChunks(
          chunks, documentVersionId, documentTitle, ir.metadata(), List.of(), section, options);
    }
    for (final TableBlock table : ir.tables()) {
      appendTableChunks(chunks, documentVersionId, documentTitle, ir.metadata(), table, options);
    }
    LOGGER.info(
        "chunk_stats documentVersionId={} strategy={} chunks={} totalTokens={}",
        documentVersionId,
        STRATEGY_ID,
        chunks.size(),
        chunks.stream().mapToInt(Chunk::tokenCount).sum());
    return chunks;
  }

  private void appendSectionChunks(
      final List<Chunk> chunks,
      final UUID documentVersionId,
      final String documentTitle,
      final Map<String, String> metadata,
      final List<String> parents,
      final Section section,
      final ChunkingOptions options) {
    final List<String> breadcrumbParents = new ArrayList<>(parents);
    final String breadcrumb =
        ChunkingSupport.breadcrumb(documentTitle, breadcrumbParents, section.heading());
    if (!section.text().isBlank()) {
      final List<ChunkingSupport.TextSpan> units =
          ChunkingSupport.paragraphOrSentenceUnits(
              section.text(), section.sourceRange(), tokenCounter, options);
      for (final ChunkingSupport.TextSpan part :
          ChunkingSupport.groupUnits(units, tokenCounter, options)) {
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

    if (!section.heading().isBlank()) {
      breadcrumbParents.add(section.heading());
    }
    for (final Section child : section.children()) {
      appendSectionChunks(
          chunks, documentVersionId, documentTitle, metadata, breadcrumbParents, child, options);
    }
  }

  private void appendTableChunks(
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

  public static final class UnchunkableContentException extends RuntimeException {
    public UnchunkableContentException(final String message) {
      super(message);
    }
  }
}
