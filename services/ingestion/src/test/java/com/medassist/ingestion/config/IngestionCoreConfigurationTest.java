package com.medassist.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.medassist.ingestion.chunking.Chunker;
import com.medassist.ingestion.chunking.SentenceEmbeddingProvider;
import com.medassist.ingestion.context.ContextCostGateException;
import com.medassist.ingestion.context.ContextDocument;
import com.medassist.ingestion.context.ContextLlmClient;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.context.ContextualRetrievalRequest;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.context.JdbcContextCache;
import com.medassist.ingestion.discovery.DocumentFingerprintRepository;
import com.medassist.ingestion.discovery.ObjectStoreCatalog;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingResponse;
import com.medassist.ingestion.pipeline.index.ChunkingStrategyRegistry;
import com.medassist.ingestion.pipeline.index.DocumentIndexingProcessor;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.index.EmbeddingVector;
import com.medassist.ingestion.pipeline.parse.DeidentificationClient;
import com.medassist.ingestion.pipeline.parse.ParseAndDeidentifyProcessor;
import com.medassist.ingestion.pipeline.parse.ParserClient;
import com.medassist.ingestion.pipeline.scan.PhiDetectionResponse;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class IngestionCoreConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(
              TestPropertiesConfiguration.class,
              TestDependencies.class,
              IngestionCoreConfiguration.class);

  @Test
  void wiresDefaultProductionComponentsWithoutAmbiguousChunkers() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ParseAndDeidentifyProcessor.class);
          assertThat(context).hasSingleBean(DocumentIndexingProcessor.class);
          assertThat(context).hasSingleBean(SentenceEmbeddingProvider.class);
          assertThat(context).hasSingleBean(ChunkingStrategyRegistry.class);
          assertThat(context).getBeans(Chunker.class).hasSize(3);

          final ChunkingStrategyRegistry registry = context.getBean(ChunkingStrategyRegistry.class);
          assertThat(registry.resolve("structure-v1"))
              .isSameAs(context.getBean("structureChunker"));
          assertThat(registry.resolve("fixed-v1")).isSameAs(context.getBean("fixedChunker"));
          assertThat(registry.resolve("semantic-v1")).isSameAs(context.getBean("semanticChunker"));
          assertThat(registry.resolve("STRUCTURE-V1")).isNull();
          assertThat(registry.resolve("structure")).isNull();

          final EmbeddingModel model = context.getBean(EmbeddingModel.class);
          assertThat(model.name()).isEqualTo("bge-m3");
          assertThat(model.version()).isEqualTo("m1-baseline");
          assertThat(model.dimension()).isEqualTo(1024);

          final ContextLlmClient llmClient = context.getBean(ContextLlmClient.class);
          assertThatThrownBy(() -> llmClient.generate(null))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("context LLM provider is not configured");
        });
  }

  @Test
  void rejectsNonPositiveTimeoutAtBindingBoundary() {
    contextRunner
        .withPropertyValues("medassist.ingestion.phi-scan-timeout=0s")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsUnknownDefaultChunkingStrategy() {
    contextRunner
        .withPropertyValues("medassist.chunking.default-strategy-id=approximate")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsModelDimensionDrift() {
    contextRunner
        .withPropertyValues("medassist.ingestion.embedding-model.dimension=768")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void llmModeChecksApprovedCostBeforeCallingUnavailableProvider() {
    contextRunner
        .withPropertyValues(
            "medassist.ingestion.context.approved-cost-artifact=target/missing-cost-artifact.json")
        .run(
            context -> {
              final ContextualRetrievalService service =
                  context.getBean(ContextualRetrievalService.class);
              final ContextualRetrievalRequest request =
                  new ContextualRetrievalRequest(
                      new ContextDocument(UUID.randomUUID(), "Title", "Publisher", "Summary"),
                      ContextualRetrievalMode.LLM_GENERATED,
                      "context-v1",
                      List.of());

              assertThatThrownBy(() -> service.prepare(request))
                  .isInstanceOf(ContextCostGateException.class)
                  .hasMessage("approved cost artifact is missing or unreadable");
            });
  }

  @Test
  void semanticProviderRejectsResponseWithMoreThanOneVector() {
    final IngestionCoreConfiguration configuration = new IngestionCoreConfiguration();
    final EmbeddingModel model = new EmbeddingModel("bge-m3", "m1-baseline", 2);
    final BatchEmbeddingPort port =
        request ->
            new BatchEmbeddingResponse(
                model.name(),
                model.version(),
                model.dimension(),
                List.of(
                    new EmbeddingVector(List.of(1.0f, 0.0f)),
                    new EmbeddingVector(List.of(0.0f, 1.0f))));

    final SentenceEmbeddingProvider provider = configuration.sentenceEmbeddingProvider(port, model);

    assertThatThrownBy(() -> provider.embed("sentence"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("semantic embedding response violates the model contract");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({IngestionProperties.class, ChunkingProperties.class})
  static class TestPropertiesConfiguration {}

  @Configuration(proxyBeanMethods = false)
  static class TestDependencies {
    @Bean
    ObjectStoreCatalog objectStoreCatalog() {
      return () -> List.of();
    }

    @Bean
    DocumentFingerprintRepository documentFingerprintRepository() {
      return (sourceId, storageUri) -> java.util.Optional.empty();
    }

    @Bean
    ParserClient parserClient() {
      return request -> null;
    }

    @Bean
    DeidentificationClient deidentificationClient() {
      return request -> null;
    }

    @Bean
    BatchEmbeddingPort batchEmbeddingPort() {
      return request -> {
        final int dimension = request.model().dimension();
        final List<Float> values = Collections.nCopies(dimension, 0.0f);
        return new BatchEmbeddingResponse(
            request.model().name(),
            request.model().version(),
            dimension,
            request.inputs().stream().map(input -> new EmbeddingVector(values)).toList());
      };
    }

    @Bean
    PostDeidentificationPhiScanner postDeidentificationPhiScanner() {
      return new PostDeidentificationPhiScanner(
          request -> new PhiDetectionResponse(java.util.Set.of()));
    }

    @Bean
    JdbcContextCache jdbcContextCache() {
      return new JdbcContextCache(mock(NamedParameterJdbcTemplate.class));
    }
  }
}
