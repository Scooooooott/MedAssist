package com.medassist.ingestion.config;

import com.medassist.ingestion.chunking.Chunker;
import com.medassist.ingestion.chunking.ChunkingOptions;
import com.medassist.ingestion.chunking.FixedLengthChunker;
import com.medassist.ingestion.chunking.SemanticChunker;
import com.medassist.ingestion.chunking.SentenceEmbeddingProvider;
import com.medassist.ingestion.chunking.SimpleTokenCounter;
import com.medassist.ingestion.chunking.StructureAwareChunker;
import com.medassist.ingestion.chunking.TokenCounter;
import com.medassist.ingestion.context.ApprovedCostGate;
import com.medassist.ingestion.context.ContextLlmClient;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.context.JdbcContextCache;
import com.medassist.ingestion.discovery.DocumentFingerprintRepository;
import com.medassist.ingestion.discovery.ObjectDiscoveryService;
import com.medassist.ingestion.discovery.ObjectStoreCatalog;
import com.medassist.ingestion.discovery.Sha256Hasher;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingRequest;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingResponse;
import com.medassist.ingestion.pipeline.index.ChunkingStrategyRegistry;
import com.medassist.ingestion.pipeline.index.DocumentIndexingProcessor;
import com.medassist.ingestion.pipeline.index.EmbeddingInput;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.index.EmbeddingVector;
import com.medassist.ingestion.pipeline.parse.DeidentificationClient;
import com.medassist.ingestion.pipeline.parse.ParseAndDeidentifyProcessor;
import com.medassist.ingestion.pipeline.parse.ParserClient;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production wiring for ingestion application-layer components. */
@Configuration
public class IngestionCoreConfiguration {
  @Bean
  Sha256Hasher sha256Hasher() {
    return new Sha256Hasher();
  }

  @Bean
  ObjectDiscoveryService objectDiscoveryService(
      final ObjectStoreCatalog catalog,
      final DocumentFingerprintRepository fingerprintRepository,
      final Sha256Hasher hasher) {
    return new ObjectDiscoveryService(catalog, fingerprintRepository, hasher);
  }

  @Bean
  ParseAndDeidentifyProcessor parseAndDeidentifyProcessor(
      final ParserClient parserClient,
      final DeidentificationClient deidentificationClient,
      final IngestionProperties properties) {
    return new ParseAndDeidentifyProcessor(
        parserClient,
        deidentificationClient,
        properties.getParserTimeout(),
        properties.getDeidTimeout(),
        properties.getDeidentificationPolicy());
  }

  @Bean
  TokenCounter tokenCounter() {
    return new SimpleTokenCounter();
  }

  @Bean
  EmbeddingModel ingestionEmbeddingModel(final IngestionProperties properties) {
    final IngestionProperties.EmbeddingModel configured = properties.getEmbeddingModel();
    return new EmbeddingModel(
        configured.getName(), configured.getVersion(), configured.getDimension());
  }

  @Bean
  SentenceEmbeddingProvider sentenceEmbeddingProvider(
      final BatchEmbeddingPort embeddingPort, final EmbeddingModel ingestionEmbeddingModel) {
    return sentence -> {
      final UUID inputId = UUID.nameUUIDFromBytes(sentence.getBytes(StandardCharsets.UTF_8));
      final BatchEmbeddingResponse response =
          embeddingPort.embed(
              new BatchEmbeddingRequest(
                  ingestionEmbeddingModel, List.of(new EmbeddingInput(inputId, sentence))));
      validateSingleVector(ingestionEmbeddingModel, response);
      final EmbeddingVector vector = response.vectors().getFirst();
      final double[] values = new double[vector.values().size()];
      for (int index = 0; index < values.length; index++) {
        values[index] = vector.values().get(index);
      }
      return values;
    };
  }

  @Bean(name = "structureChunker")
  Chunker structureChunker(final TokenCounter tokenCounter) {
    return new StructureAwareChunker(tokenCounter);
  }

  @Bean(name = "fixedChunker")
  Chunker fixedChunker(final TokenCounter tokenCounter) {
    return new FixedLengthChunker(tokenCounter);
  }

  @Bean(name = "semanticChunker")
  Chunker semanticChunker(
      final SentenceEmbeddingProvider embeddingProvider,
      final TokenCounter tokenCounter,
      final ChunkingProperties properties) {
    return new SemanticChunker(
        embeddingProvider, tokenCounter, properties.getSemanticBreakpointThreshold());
  }

  @Bean
  ChunkingStrategyRegistry chunkingStrategyRegistry(
      @Qualifier("structureChunker") final Chunker structureChunker,
      @Qualifier("fixedChunker") final Chunker fixedChunker,
      @Qualifier("semanticChunker") final Chunker semanticChunker) {
    final Map<String, Chunker> strategies =
        Map.of(
            "structure-v1", structureChunker,
            "fixed-v1", fixedChunker,
            "semantic-v1", semanticChunker);
    return strategies::get;
  }

  @Bean
  ChunkingOptions chunkingOptions(final ChunkingProperties properties) {
    return new ChunkingOptions(
        properties.getTargetTokens(),
        properties.getMaxTokens(),
        properties.getMinTokens(),
        properties.getOverlapTokens());
  }

  @Bean
  ApprovedCostGate approvedCostGate(final IngestionProperties properties) {
    return new ApprovedCostGate(
        properties.getContext().getApprovedCostArtifact(),
        properties.getContext().getApprovedCostArtifactSha256());
  }

  @Bean
  @ConditionalOnMissingBean(ContextLlmClient.class)
  ContextLlmClient contextLlmClient() {
    return request -> {
      throw new IllegalStateException("context LLM provider is not configured");
    };
  }

  @Bean
  ContextualRetrievalService contextualRetrievalService(
      final JdbcContextCache cache,
      final ContextLlmClient llmClient,
      final ApprovedCostGate costGate) {
    return new ContextualRetrievalService(cache, llmClient, costGate);
  }

  @Bean
  DocumentIndexingProcessor documentIndexingProcessor(
      final ChunkingStrategyRegistry strategies,
      final ContextualRetrievalService contextualRetrievalService,
      final BatchEmbeddingPort embeddingPort,
      final PostDeidentificationPhiScanner phiScanner,
      final IngestionProperties properties) {
    return new DocumentIndexingProcessor(
        strategies,
        contextualRetrievalService,
        embeddingPort,
        phiScanner,
        properties.getPhiScanTimeout());
  }

  private static void validateSingleVector(
      final EmbeddingModel expected, final BatchEmbeddingResponse response) {
    if (response == null
        || !expected.name().equals(response.modelName())
        || !expected.version().equals(response.modelVersion())
        || expected.dimension() != response.dimension()
        || response.vectors().size() != 1
        || response.vectors().getFirst().values().size() != expected.dimension()) {
      throw new IllegalStateException("semantic embedding response violates the model contract");
    }
  }
}
