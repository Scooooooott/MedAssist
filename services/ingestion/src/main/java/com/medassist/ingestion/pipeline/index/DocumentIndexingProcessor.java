package com.medassist.ingestion.pipeline.index;

import com.medassist.domain.Chunk;
import com.medassist.domain.DocumentIR;
import com.medassist.ingestion.chunking.Chunker;
import com.medassist.ingestion.context.ContextDocument;
import com.medassist.ingestion.context.ContextualChunk;
import com.medassist.ingestion.context.ContextualRetrievalRequest;
import com.medassist.ingestion.context.ContextualRetrievalResult;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import com.medassist.ingestion.pipeline.scan.PhiDetectionException;
import com.medassist.ingestion.pipeline.scan.PhiDetectionTransientException;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScan;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure application orchestration for chunking, contextual embedding, and contract validation. */
public final class DocumentIndexingProcessor {
  private final ChunkingStrategyRegistry chunkingStrategies;
  private final ContextualRetrievalService contextualRetrievalService;
  private final BatchEmbeddingPort embeddingPort;
  private final PostDeidentificationPhiScanner phiScanner;
  private final Duration phiScanTimeout;

  public DocumentIndexingProcessor(
      final ChunkingStrategyRegistry chunkingStrategies,
      final ContextualRetrievalService contextualRetrievalService,
      final BatchEmbeddingPort embeddingPort,
      final PostDeidentificationPhiScanner phiScanner,
      final Duration phiScanTimeout) {
    this.chunkingStrategies = Objects.requireNonNull(chunkingStrategies, "chunkingStrategies");
    this.contextualRetrievalService =
        Objects.requireNonNull(contextualRetrievalService, "contextualRetrievalService");
    this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort");
    this.phiScanner = Objects.requireNonNull(phiScanner, "phiScanner");
    this.phiScanTimeout = Objects.requireNonNull(phiScanTimeout, "phiScanTimeout");
    if (phiScanTimeout.isZero() || phiScanTimeout.isNegative()) {
      throw new IllegalArgumentException("phiScanTimeout must be positive");
    }
  }

  public IndexingResult process(final IndexingRequest request) {
    Objects.requireNonNull(request, "request");
    final ParseAndDeidentifyState state = request.state();
    if (state.status() == ProcessingStatus.QUARANTINED || state.deidentifiedDocument() == null) {
      throw new IndexingException("document is not safe to index");
    }

    final Chunker chunker = resolveChunker(request.chunkingStrategyId());
    final DocumentIR ir = state.deidentifiedDocument();
    final List<Chunk> chunks =
        chunker.chunk(
            state.documentVersionId(), request.documentTitle(), ir, request.chunkingOptions());
    final ContextualRetrievalResult contextual =
        contextualRetrievalService.prepare(
            new ContextualRetrievalRequest(
                request.contextDocument(),
                request.contextualRetrievalMode(),
                request.contextPromptVersion(),
                chunks));
    if (contextual.chunks().size() != chunks.size()) {
      throw new IndexingException("contextual retrieval returned an unexpected chunk count");
    }

    final List<IndexableChunk> indexableChunks = new ArrayList<>();
    final List<EmbeddingInput> embeddingInputs = new ArrayList<>();
    for (final ContextualChunk contextualChunk : contextual.chunks()) {
      final Chunk chunk = contextualChunk.chunk();
      final String strategyId = chunk.metadata().get("chunking_strategy_id");
      if (!request.chunkingStrategyId().equals(strategyId)) {
        throw new IndexingException("chunking strategy metadata mismatch");
      }
      final PostDeidentificationPhiScan phiScan = scan(contextualChunk);
      indexableChunks.add(toIndexableChunk(chunk, contextualChunk, phiScan));
      if (phiScan.status() == PhiScanStatus.CLEAN) {
        embeddingInputs.add(new EmbeddingInput(chunk.id(), contextualChunk.embeddingText()));
      }
    }

    final List<IndexableEmbedding> embeddings = new ArrayList<>();
    if (!embeddingInputs.isEmpty()) {
      final BatchEmbeddingResponse response =
          embeddingPort.embed(new BatchEmbeddingRequest(request.embeddingModel(), embeddingInputs));
      validateEmbeddingResponse(request.embeddingModel(), embeddingInputs, response);
      for (int index = 0; index < response.vectors().size(); index++) {
        final EmbeddingVector vector = response.vectors().get(index);
        embeddings.add(
            new IndexableEmbedding(
                embeddingInputs.get(index).chunkId(),
                response.modelName(),
                response.modelVersion(),
                response.dimension(),
                vector.values()));
      }
    }
    return new IndexingResult(toIndexableDocument(request), indexableChunks, embeddings);
  }

  private PostDeidentificationPhiScan scan(final ContextualChunk contextualChunk) {
    final List<String> fragments = new ArrayList<>();
    fragments.add(contextualChunk.originalText());
    if (!contextualChunk.contextPrefix().isBlank()) {
      fragments.add(contextualChunk.contextPrefix());
    }
    try {
      return phiScanner.scan(fragments, phiScanTimeout);
    } catch (final PhiDetectionTransientException exception) {
      throw new IndexingTransientException(
          "post-de-identification PHI scan is unavailable", exception);
    } catch (final PhiDetectionException exception) {
      return new PostDeidentificationPhiScan(PhiScanStatus.FAILED, java.util.Set.of());
    }
  }

  private Chunker resolveChunker(final String strategyId) {
    try {
      final Chunker chunker = chunkingStrategies.resolve(strategyId);
      if (chunker == null) {
        throw new IndexingException("unknown chunking strategy: " + strategyId);
      }
      return chunker;
    } catch (final IndexingException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new IndexingException("unable to resolve chunking strategy: " + strategyId);
    }
  }

  private static IndexableChunk toIndexableChunk(
      final Chunk chunk,
      final ContextualChunk contextualChunk,
      final PostDeidentificationPhiScan phiScan) {
    final Map<String, String> metadata = chunk.metadata();
    final String breadcrumb = metadata.getOrDefault("breadcrumb", "");
    return new IndexableChunk(
        chunk.id(),
        chunk.documentVersionId(),
        chunk.ordinal(),
        chunk.sectionPath(),
        chunk.text(),
        chunk.tokenCount(),
        chunk.sourceRange(),
        breadcrumb,
        metadata.get("chunking_strategy_id"),
        phiScan.status(),
        phiScan.entityTypes(),
        contextualChunk.contextPrefix());
  }

  private static void validateEmbeddingResponse(
      final EmbeddingModel expected,
      final List<EmbeddingInput> inputs,
      final BatchEmbeddingResponse response) {
    if (response == null) {
      throw new IndexingException("embedding service returned no response");
    }
    if (response.vectors().size() != inputs.size()) {
      throw new IndexingException("embedding response count does not match request");
    }
    if (!expected.name().equals(response.modelName())
        || !expected.version().equals(response.modelVersion())) {
      throw new IndexingException("embedding model identity does not match request");
    }
    if (expected.dimension() != response.dimension()) {
      throw new IndexingException("embedding dimension does not match request");
    }
    for (final EmbeddingVector vector : response.vectors()) {
      if (vector.values().size() != expected.dimension()) {
        throw new IndexingException("embedding vector dimension does not match request");
      }
    }
  }

  private static IndexableDocument toIndexableDocument(final IndexingRequest request) {
    final ParseAndDeidentifyState state = request.state();
    final ContextDocument context = request.contextDocument();
    final Map<String, String> metadata =
        Map.of("title", context.title(), "publisher", context.publisher());
    return new IndexableDocument(
        state.logicalDocumentId(),
        state.documentVersionId(),
        state.discoveryResult().object().sourceId(),
        context.title(),
        context.publisher(),
        state.policyVersion(),
        metadata);
  }
}
