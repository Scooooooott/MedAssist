package com.medassist.ingestion.batch;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
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
      final Step indexStep) {
    return new JobBuilder("documentIngestionJob", jobRepository)
        .listener(jobListener())
        .start(discoverDocumentsStep)
        .next(parseAndDeidentifyStep)
        .next(chunkAndEmbedStep)
        .next(indexStep)
        .build();
  }

  @Bean
  Step discoverDocumentsStep(
      final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
    return skeletonStep(jobRepository, transactionManager, "discoverDocumentsStep");
  }

  @Bean
  Step parseAndDeidentifyStep(
      final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
    return skeletonStep(jobRepository, transactionManager, "parseAndDeidentifyStep");
  }

  @Bean
  Step chunkAndEmbedStep(
      final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
    return skeletonStep(jobRepository, transactionManager, "chunkAndEmbedStep");
  }

  @Bean
  Step indexStep(final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
    return skeletonStep(jobRepository, transactionManager, "indexStep");
  }

  private Step skeletonStep(
      final JobRepository jobRepository,
      final PlatformTransactionManager transactionManager,
      final String name) {
    return new StepBuilder(name, jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              LOGGER.info("{} skeleton executed at {}", name, Instant.now());
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .listener(stepListener())
        .build();
  }

  private JobExecutionListener jobListener() {
    return new JobExecutionListener() {
      @Override
      public void beforeJob(final JobExecution jobExecution) {
        LOGGER.info("documentIngestionJob started: executionId={}", jobExecution.getId());
      }

      @Override
      public void afterJob(final JobExecution jobExecution) {
        LOGGER.info(
            "documentIngestionJob finished: executionId={}, status={}",
            jobExecution.getId(),
            jobExecution.getStatus());
      }
    };
  }

  private StepExecutionListener stepListener() {
    return new StepExecutionListener() {
      @Override
      public void beforeStep(final StepExecution stepExecution) {
        LOGGER.info("step started: name={}, executionId={}", stepExecution.getStepName(), stepExecution.getId());
      }

      @Override
      public org.springframework.batch.core.ExitStatus afterStep(final StepExecution stepExecution) {
        LOGGER.info(
            "step finished: name={}, read={}, write={}, skip={}, durationMs={}",
            stepExecution.getStepName(),
            stepExecution.getReadCount(),
            stepExecution.getWriteCount(),
            stepExecution.getSkipCount(),
            stepExecution.getEndTime() == null || stepExecution.getStartTime() == null
                ? -1
                : java.time.Duration.between(stepExecution.getStartTime(), stepExecution.getEndTime()).toMillis());
        return stepExecution.getExitStatus();
      }
    };
  }
}
