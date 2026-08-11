package com.medassist.ingestion.batch;

import com.medassist.ingestion.batch.audit.IngestionAuditListener;
import com.medassist.ingestion.batch.audit.IngestionAuditRepository;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.steps.DiscoverObjectsTasklet;
import com.medassist.ingestion.batch.steps.ParseAndDeidentifyTasklet;
import com.medassist.ingestion.batch.steps.index.IndexPreparationConfiguration;
import com.medassist.ingestion.batch.steps.index.IndexPreparationTasklet;
import com.medassist.ingestion.batch.steps.store.DefaultIndexingPersistenceRequestFactory;
import com.medassist.ingestion.batch.steps.store.IndexingPersistenceRequestFactory;
import com.medassist.ingestion.batch.steps.store.PublishIndexTasklet;
import com.medassist.ingestion.chunking.ChunkingOptions;
import com.medassist.ingestion.config.ChunkingProperties;
import com.medassist.ingestion.config.IngestionProperties;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.context.backfill.ContextBackfillRepository;
import com.medassist.ingestion.context.backfill.ContextBackfillTasklet;
import com.medassist.ingestion.discovery.ObjectDiscoveryService;
import com.medassist.ingestion.discovery.ObjectStoreCatalog;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.index.DocumentIndexingProcessor;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.parse.ParseAndDeidentifyProcessor;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import com.medassist.ingestion.pipeline.store.IndexingPersistencePort;
import com.medassist.ingestion.versioning.DocumentVersionMetadataExtractor;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class IngestionBatchConfiguration {
  private static final Logger LOGGER = LoggerFactory.getLogger(IngestionBatchConfiguration.class);

  @Bean
  Job documentIngestionJob(
      final JobRepository jobRepository,
      final Step discoverDocumentsStep,
      final Step parseAndDeidentifyStep,
      final Step chunkAndEmbedStep,
      final Step indexStep,
      final JobExecutionListener ingestionJobMutexListener,
      final IngestionAuditListener ingestionAuditListener) {
    return new JobBuilder("documentIngestionJob", jobRepository)
        .listener(ingestionJobMutexListener)
        .listener(ingestionAuditListener)
        .start(discoverDocumentsStep)
        .next(parseAndDeidentifyStep)
        .next(chunkAndEmbedStep)
        .next(indexStep)
        .build();
  }

  @Bean
  Job contextBackfillJob(
      final JobRepository jobRepository,
      final Step contextBackfillStep,
      final JobExecutionListener ingestionJobMutexListener) {
    return new JobBuilder("contextBackfillJob", jobRepository)
        .listener(ingestionJobMutexListener)
        .start(contextBackfillStep)
        .build();
  }

  @Bean
  IngestionJobMutex ingestionJobMutex() {
    return new IngestionJobMutex();
  }

  @Bean
  JobExecutionListener ingestionJobMutexListener(final IngestionJobMutex jobMutex) {
    return new JobExecutionListener() {
      @Override
      public void beforeJob(final JobExecution jobExecution) {
        LOGGER.info(
            "batch job started: jobName={}, executionId={}",
            jobName(jobExecution),
            jobExecution.getId());
      }

      @Override
      public void afterJob(final JobExecution jobExecution) {
        try {
          LOGGER.info(
              "batch job finished: jobName={}, executionId={}, status={}",
              jobName(jobExecution),
              jobExecution.getId(),
              jobExecution.getStatus());
        } finally {
          jobMutex.release();
        }
      }
    };
  }

  @Bean
  IngestionAuditListener ingestionAuditListener(final IngestionAuditRepository repository) {
    return new IngestionAuditListener(repository);
  }

  @Bean
  Tasklet discoverObjectsTasklet(
      final ObjectDiscoveryService discoveryService, final DurableStageRepository stageRepository) {
    return new DiscoverObjectsTasklet(discoveryService, stageRepository);
  }

  @Bean
  Tasklet parseAndDeidentifyTasklet(
      final ObjectStoreCatalog objectStoreCatalog,
      final DurableStageRepository stageRepository,
      final ParseAndDeidentifyProcessor processor) {
    return new ParseAndDeidentifyTasklet(objectStoreCatalog, stageRepository, processor);
  }

  @Bean
  IndexPreparationConfiguration indexPreparationConfiguration(
      final ChunkingProperties chunkingProperties,
      final ChunkingOptions chunkingOptions,
      final IngestionProperties ingestionProperties,
      final EmbeddingModel ingestionEmbeddingModel) {
    return new IndexPreparationConfiguration(
        chunkingProperties.getDefaultStrategyId(),
        chunkingOptions,
        ingestionProperties.getContext().getMode(),
        ingestionProperties.getContext().getPromptVersion(),
        ingestionEmbeddingModel);
  }

  @Bean
  Tasklet indexPreparationTasklet(
      final DurableStageRepository stageRepository,
      final DocumentIndexingProcessor processor,
      final IndexPreparationConfiguration configuration) {
    return new IndexPreparationTasklet(stageRepository, processor, configuration);
  }

  @Bean
  IndexingPersistenceRequestFactory indexingPersistenceRequestFactory(
      final IngestionProperties properties) {
    return new DefaultIndexingPersistenceRequestFactory(
        new DocumentVersionMetadataExtractor(),
        properties.getContext().getMode(),
        properties.getContext().getPromptVersion(),
        Clock.systemUTC(),
        properties.getDefaultSourceSystem(),
        properties.getDefaultDocType(),
        properties.getDefaultContentDomain());
  }

  @Bean
  Tasklet publishIndexTasklet(
      final DurableStageRepository stageRepository,
      final IndexingPersistenceRequestFactory requestFactory,
      final IndexingPersistencePort persistencePort) {
    return new PublishIndexTasklet(stageRepository, requestFactory, persistencePort);
  }

  @Bean
  Tasklet contextBackfillTasklet(
      final ContextBackfillRepository repository,
      final ContextualRetrievalService contextualRetrievalService,
      final BatchEmbeddingPort embeddingPort,
      final PostDeidentificationPhiScanner phiScanner,
      final EmbeddingModel ingestionEmbeddingModel,
      final IngestionProperties properties) {
    return new ContextBackfillTasklet(
        repository,
        contextualRetrievalService,
        embeddingPort,
        phiScanner,
        ingestionEmbeddingModel,
        properties.getContext().getMode(),
        properties.getContext().getPromptVersion(),
        properties.getPhiScanTimeout(),
        properties.getContext().getBackfillChunkLimit());
  }

  @Bean
  Step discoverDocumentsStep(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      @Qualifier("discoverObjectsTasklet") final Tasklet discoverObjectsTasklet,
      final IngestionProperties properties,
      final IngestionAuditListener auditListener) {
    return step(
        jobRepository,
        transactionManager,
        "discoverDocumentsStep",
        discoverObjectsTasklet,
        properties,
        auditListener);
  }

  @Bean
  Step parseAndDeidentifyStep(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      @Qualifier("parseAndDeidentifyTasklet") final Tasklet parseAndDeidentifyTasklet,
      final IngestionProperties properties,
      final IngestionAuditListener auditListener) {
    return step(
        jobRepository,
        transactionManager,
        "parseAndDeidentifyStep",
        parseAndDeidentifyTasklet,
        properties,
        auditListener);
  }

  @Bean
  Step chunkAndEmbedStep(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      @Qualifier("indexPreparationTasklet") final Tasklet indexPreparationTasklet,
      final IngestionProperties properties,
      final IngestionAuditListener auditListener) {
    return step(
        jobRepository,
        transactionManager,
        "chunkAndEmbedStep",
        indexPreparationTasklet,
        properties,
        auditListener);
  }

  @Bean
  Step indexStep(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      @Qualifier("publishIndexTasklet") final Tasklet publishIndexTasklet,
      final IngestionProperties properties,
      final IngestionAuditListener auditListener) {
    return step(
        jobRepository,
        transactionManager,
        "indexStep",
        publishIndexTasklet,
        properties,
        auditListener);
  }

  @Bean
  Step contextBackfillStep(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      @Qualifier("contextBackfillTasklet") final Tasklet contextBackfillTasklet,
      final IngestionProperties properties) {
    return new StepBuilder("contextBackfillStep", jobRepository)
        .tasklet(resilient(contextBackfillTasklet, properties), transactionManager)
        .build();
  }

  private static Step step(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      final String name,
      final Tasklet tasklet,
      final IngestionProperties properties,
      final IngestionAuditListener auditListener) {
    return new StepBuilder(name, jobRepository)
        .tasklet(resilient(tasklet, properties), transactionManager)
        .listener(auditListener)
        .build();
  }

  private static Tasklet resilient(final Tasklet tasklet, final IngestionProperties properties) {
    final Tasklet retried =
        new RetryingTasklet(
            tasklet,
            properties.getRetryMaxAttempts(),
            properties.getRetryInitialBackoff(),
            properties.getRetryMaxBackoff(),
            properties.getRetryMultiplier());
    return new SkipLimitTasklet(retried, properties.getSkipLimit());
  }

  private static String jobName(final JobExecution execution) {
    return execution.getJobInstance() == null ? "unknown" : execution.getJobInstance().getJobName();
  }
}
