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

/** Semantic strategy that only breaks between complete sentences. */
public final class SemanticChunker implements Chunker {
  static final String STRATEGY_ID = "semantic-v1";
  private final SentenceEmbeddingProvider embeddingProvider;
  private final TokenCounter tokenCounter;
  private final double breakpointThreshold;

  public SemanticChunker(
      final SentenceEmbeddingProvider embeddingProvider, final double breakpointThreshold) {
    this(embeddingProvider, new SimpleTokenCounter(), breakpointThreshold);
  }

  public SemanticChunker(
      final SentenceEmbeddingProvider embeddingProvider,
      final TokenCounter tokenCounter,
      final double breakpointThreshold) {
    if (embeddingProvider == null) {
      throw new IllegalArgumentException("embeddingProvider is required");
    }
    if (!Double.isFinite(breakpointThreshold)
        || breakpointThreshold < -1.0
        || breakpointThreshold > 1.0) {
      throw new IllegalArgumentException("breakpointThreshold must be between -1 and 1");
    }
    this.embeddingProvider = embeddingProvider;
    this.tokenCounter = tokenCounter;
    this.breakpointThreshold = breakpointThreshold;
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
      for (final ChunkingSupport.TextSpan part : semanticGroups(sentences, options)) {
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

  private List<ChunkingSupport.TextSpan> semanticGroups(
      final List<ChunkingSupport.TextSpan> sentences, final ChunkingOptions options) {
    final List<SemanticGroup> groups = new ArrayList<>();
    if (sentences.isEmpty()) {
      return List.of();
    }
    int groupStart = 0;
    for (int index = 1; index < sentences.size(); index++) {
      final ChunkingSupport.TextSpan candidate =
          ChunkingSupport.mergeSpans(sentences, groupStart, index + 1);
      final boolean overMax = tokenCounter.count(candidate.text()) > options.maxTokens();
      final boolean semanticBreak =
          cosine(
                  embeddingProvider.embed(sentences.get(index - 1).text()),
                  embeddingProvider.embed(sentences.get(index).text()))
              < breakpointThreshold;
      final boolean safeBreak =
          tokenCounter.count(ChunkingSupport.mergeSpans(sentences, groupStart, index).text())
              >= options.minTokens();
      if (overMax || (semanticBreak && safeBreak)) {
        groups.add(new SemanticGroup(groupStart, index));
        groupStart = index;
      }
    }
    groups.add(new SemanticGroup(groupStart, sentences.size()));
    return renderGroups(groups, sentences, options);
  }

  private List<ChunkingSupport.TextSpan> renderGroups(
      final List<SemanticGroup> groups,
      final List<ChunkingSupport.TextSpan> sentences,
      final ChunkingOptions options) {
    final List<ChunkingSupport.TextSpan> result = new ArrayList<>();
    for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
      final SemanticGroup group = groups.get(groupIndex);
      int start = group.start();
      if (groupIndex > 0 && options.overlapTokens() > 0) {
        int overlapTokens = 0;
        int overlapStart = group.start();
        while (overlapStart > groups.get(groupIndex - 1).start()) {
          final int candidateIndex = overlapStart - 1;
          final int candidateTokens = tokenCounter.count(sentences.get(candidateIndex).text());
          if (overlapTokens + candidateTokens > options.overlapTokens()) {
            break;
          }
          overlapTokens += candidateTokens;
          overlapStart = candidateIndex;
        }
        final ChunkingSupport.TextSpan overlapped =
            ChunkingSupport.mergeSpans(sentences, overlapStart, group.end());
        if (tokenCounter.count(overlapped.text()) <= options.maxTokens()) {
          start = overlapStart;
        }
      }
      final ChunkingSupport.TextSpan groupSpan =
          ChunkingSupport.mergeSpans(sentences, start, group.end());
      if (tokenCounter.count(groupSpan.text()) <= options.maxTokens()) {
        result.add(groupSpan);
      } else {
        for (final ChunkingSupport.TextSpan split :
            ChunkingSupport.groupUnits(
                ChunkingSupport.sentenceUnits(
                    groupSpan.text(), groupSpan.range(), tokenCounter, options),
                tokenCounter,
                options)) {
          result.add(split.rebase(groupSpan.sourceText(), groupSpan.localStart()));
        }
      }
    }
    return result;
  }

  private double cosine(final double[] left, final double[] right) {
    if (left == null || right == null || left.length == 0 || left.length != right.length) {
      throw new IllegalArgumentException(
          "sentence embeddings must have the same non-empty dimension");
    }
    double dot = 0.0;
    double leftNorm = 0.0;
    double rightNorm = 0.0;
    for (int index = 0; index < left.length; index++) {
      dot += left[index] * right[index];
      leftNorm += left[index] * left[index];
      rightNorm += right[index] * right[index];
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) {
      return 0.0;
    }
    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
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

  private record SemanticGroup(int start, int end) {}
}
