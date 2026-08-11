package com.medassist.ingestion.pipeline.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.domain.Chunk;
import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.domain.SourceRange;
import com.medassist.ingestion.chunking.ChunkingOptions;
import com.medassist.ingestion.context.ContextDocument;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.context.InMemoryContextCache;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.discovery.ObjectDescriptor;
import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import com.medassist.ingestion.pipeline.model.FailureStage;
import com.medassist.ingestion.pipeline.model.IngestionWorkItem;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import com.medassist.ingestion.pipeline.scan.PhiDetectionPort;
import com.medassist.ingestion.pipeline.scan.PhiDetectionResponse;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DocumentIndexingProcessorTest {
  private static final UUID DOCUMENT_VERSION_ID = UUID.randomUUID();
  private static final String STRATEGY = "structure-v1";
  private static final EmbeddingModel MODEL = new EmbeddingModel("medical-embed", "v1", 3);

  @Test
  void rejectsQuarantinedStateBeforeChunkingOrEmbedding() {
    final AtomicReference<Boolean> chunked = new AtomicReference<>(false);
    final AtomicReference<Boolean> embedded = new AtomicReference<>(false);
    final DocumentIndexingProcessor processor =
        processor(
            strategy -> {
              chunked.set(true);
              return (version, title, ir, options) -> List.of();
            },
            request -> {
              embedded.set(true);
              return response(request, List.of());
            });

    assertThrows(IndexingException.class, () -> processor.process(request(quarantinedState())));
    assertEquals(false, chunked.get());
    assertEquals(false, embedded.get());
  }

  @Test
  void selectsExplicitStrategyAndPreservesSourceRangeAndPhiStatus() {
    final AtomicReference<String> selected = new AtomicReference<>();
    final Chunk chunk = chunk("deidentified text", new SourceRange(12, 29), STRATEGY);
    final DocumentIndexingProcessor processor =
        processor(
            strategy -> {
              selected.set(strategy);
              return (version, title, ir, options) -> List.of(chunk);
            },
            request -> response(request, List.of(vector(3))));

    final IndexingResult result = processor.process(request(successfulState()));

    assertEquals(STRATEGY, selected.get());
    assertEquals("deidentified text", result.chunks().get(0).text());
    assertEquals(new SourceRange(12, 29), result.chunks().get(0).sourceRange());
    assertEquals(PhiScanStatus.CLEAN, result.chunks().get(0).phiScanStatus());
    assertEquals(STRATEGY, result.chunks().get(0).chunkingStrategyId());
    assertFalse(result.document().metadata().containsKey("unsafe"));
  }

  @Test
  void sendsContextOnlyToEmbeddingAndKeepsChunkTextForIndexAndDisplay() {
    final Chunk chunk = chunk("source-faithful text", new SourceRange(0, 21), STRATEGY);
    final AtomicReference<BatchEmbeddingRequest> embeddingRequest = new AtomicReference<>();
    final DocumentIndexingProcessor processor =
        processor(
            strategy -> (version, title, ir, options) -> List.of(chunk),
            request -> {
              embeddingRequest.set(request);
              return response(request, List.of(vector(3)));
            });

    final IndexingResult result = processor.process(request(successfulState()));
    final IndexableChunk output = result.chunks().get(0);

    assertTrue(embeddingRequest.get().inputs().get(0).text().contains("Document title: Synthetic"));
    assertTrue(embeddingRequest.get().inputs().get(0).text().contains("source-faithful text"));
    assertEquals("source-faithful text", output.text());
    assertEquals("source-faithful text", output.text());
    assertEquals(
        "Document title: Synthetic\nPublisher: Publisher\nDocument summary: Summary\n"
            + "Breadcrumb: Synthetic > Section",
        output.contextPrefix());
    assertEquals("source-faithful text", result.chunks().get(0).text());
  }

  @Test
  void rejectsEmbeddingCountDimensionAndModelVersionMismatches() {
    final Chunk chunk = chunk("deidentified text", new SourceRange(0, 17), STRATEGY);
    final DocumentIndexingProcessor countProcessor =
        processor(
            strategy -> (version, title, ir, options) -> List.of(chunk),
            request -> response(request, List.of()));
    assertThrows(IndexingException.class, () -> countProcessor.process(request(successfulState())));

    final DocumentIndexingProcessor dimensionProcessor =
        processor(
            strategy -> (version, title, ir, options) -> List.of(chunk),
            request ->
                new BatchEmbeddingResponse(
                    request.model().name(),
                    request.model().version(),
                    request.model().dimension(),
                    List.of(vector(2))));
    assertThrows(
        IndexingException.class, () -> dimensionProcessor.process(request(successfulState())));

    final DocumentIndexingProcessor versionProcessor =
        processor(
            strategy -> (version, title, ir, options) -> List.of(chunk),
            request ->
                new BatchEmbeddingResponse(
                    request.model().name(),
                    "wrong-version",
                    request.model().dimension(),
                    List.of(vector(3))));
    assertThrows(
        IndexingException.class, () -> versionProcessor.process(request(successfulState())));
  }

  @Test
  void outputCollectionsAreImmutable() {
    final Chunk chunk = chunk("deidentified text", new SourceRange(0, 17), STRATEGY);
    final DocumentIndexingProcessor processor =
        processor(
            strategy -> (version, title, ir, options) -> List.of(chunk),
            request -> response(request, List.of(vector(3))));

    final IndexingResult result = processor.process(request(successfulState()));

    assertThrows(UnsupportedOperationException.class, () -> result.chunks().add(null));
    assertThrows(UnsupportedOperationException.class, () -> result.embeddings().add(null));
  }

  @Test
  void suspectChunkKeepsEntityTypesButIsNeverSentForEmbedding() {
    final Chunk chunk = chunk("deidentified text", new SourceRange(0, 17), STRATEGY);
    final DocumentIndexingProcessor processor =
        processor(
            strategy -> (version, title, ir, options) -> List.of(chunk),
            request -> {
              throw new AssertionError("suspect chunk must not be embedded");
            },
            request -> new PhiDetectionResponse(java.util.Set.of("PERSON")));

    final IndexingResult result = processor.process(request(successfulState()));

    assertEquals(PhiScanStatus.SUSPECT, result.chunks().get(0).phiScanStatus());
    assertEquals(java.util.Set.of("PERSON"), result.chunks().get(0).phiEntityTypes());
    assertTrue(result.embeddings().isEmpty());
  }

  private static DocumentIndexingProcessor processor(
      final ChunkingStrategyRegistry registry, final BatchEmbeddingPort embeddings) {
    return processor(registry, embeddings, request -> new PhiDetectionResponse(java.util.Set.of()));
  }

  private static DocumentIndexingProcessor processor(
      final ChunkingStrategyRegistry registry,
      final BatchEmbeddingPort embeddings,
      final PhiDetectionPort phiDetection) {
    return new DocumentIndexingProcessor(
        registry,
        new ContextualRetrievalService(
            new InMemoryContextCache(),
            request -> new com.medassist.ingestion.context.ContextLlmResponse("llm-context"),
            new com.medassist.ingestion.context.ApprovedCostGate(
                Path.of("missing-cost-artifact"),
                "0000000000000000000000000000000000000000000000000000000000000000")),
        embeddings,
        new PostDeidentificationPhiScanner(phiDetection),
        java.time.Duration.ofSeconds(1));
  }

  private static IndexingRequest request(final ParseAndDeidentifyState state) {
    return new IndexingRequest(
        state,
        "Synthetic",
        STRATEGY,
        new ChunkingOptions(10, 20, 1, 0),
        new ContextDocument(DOCUMENT_VERSION_ID, "Synthetic", "Publisher", "Summary"),
        ContextualRetrievalMode.RULE_BASED,
        "context-v1",
        MODEL);
  }

  private static ParseAndDeidentifyState successfulState() {
    final DocumentIR ir =
        new DocumentIR(
            List.of(
                new Section(
                    "1", "Section", 1, "deidentified text", List.of(), new SourceRange(0, 17))),
            List.of(),
            Map.of("title", "Synthetic", "unsafe", "RAW_PHI_TOKEN"));
    return new ParseAndDeidentifyState(
        workItem(),
        ir,
        Map.of(),
        "policy-v1",
        List.of(),
        ProcessingStatus.SUCCEEDED,
        FailureStage.NONE,
        "");
  }

  private static ParseAndDeidentifyState quarantinedState() {
    return new ParseAndDeidentifyState(
        workItem(),
        null,
        Map.of(),
        "",
        List.of(),
        ProcessingStatus.QUARANTINED,
        FailureStage.PARSE,
        "parser failure");
  }

  private static IngestionWorkItem workItem() {
    final ObjectDescriptor descriptor =
        new ObjectDescriptor(
            URI.create("s3://raw/synthetic"),
            "synthetic-source",
            "text/plain",
            10,
            Map.of(),
            () -> new ByteArrayInputStream("synthetic".getBytes(StandardCharsets.UTF_8)));
    final ObjectDiscoveryResult discovery =
        new ObjectDiscoveryResult(
            descriptor, "hash", Optional.empty(), DiscoveryClassification.NEW, true);
    return new IngestionWorkItem(discovery, UUID.randomUUID(), DOCUMENT_VERSION_ID);
  }

  private static Chunk chunk(final String text, final SourceRange range, final String strategy) {
    return new Chunk(
        UUID.randomUUID(),
        DOCUMENT_VERSION_ID,
        0,
        "1",
        text,
        3,
        range,
        Map.of("breadcrumb", "Synthetic > Section", "chunking_strategy_id", strategy));
  }

  private static BatchEmbeddingResponse response(
      final BatchEmbeddingRequest request, final List<EmbeddingVector> vectors) {
    return new BatchEmbeddingResponse(
        request.model().name(), request.model().version(), request.model().dimension(), vectors);
  }

  private static EmbeddingVector vector(final int dimension) {
    return new EmbeddingVector(
        java.util.stream.IntStream.range(0, dimension).mapToObj(index -> (float) index).toList());
  }
}
