package com.medassist.auditgovernance.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medassist.auditgovernance.AuditChainStore;
import com.medassist.auditgovernance.AuditEventPublisher;
import com.medassist.auditgovernance.InMemoryAuditEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditTransportProperties.class)
@EnableScheduling
public class AuditTransportConfiguration {
  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper auditObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  @Bean
  AuditEventCodec auditEventCodec(final AuditEventValidator validator) {
    return new AuditEventCodec(validator);
  }

  @Bean
  AuditEventValidator auditEventValidator() {
    return new AuditEventValidator();
  }

  @Bean
  AuditTransportMetrics auditTransportMetrics(final MeterRegistry registry) {
    return new AuditTransportMetrics(registry);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "medassist.audit.transport",
      name = "mode",
      havingValue = "direct",
      matchIfMissing = true)
  InMemoryAuditEventPublisher auditChainPublisher() {
    return new InMemoryAuditEventPublisher();
  }

  @Bean
  AuditEventProcessor auditEventProcessor(
      final AuditEventValidator validator,
      final AuditChainStore chainStore,
      final AuditTransportMetrics metrics) {
    return new AuditEventProcessor(validator, chainStore, metrics);
  }

  @Bean
  AuditChainIntegrityMonitor auditChainIntegrityMonitor(
      final AuditChainStore chainStore, final MeterRegistry meterRegistry) {
    return new AuditChainIntegrityMonitor(chainStore, meterRegistry);
  }

  @Bean(name = "auditEventPublisher")
  @Primary
  @ConditionalOnProperty(
      prefix = "medassist.audit.transport",
      name = "mode",
      havingValue = "direct",
      matchIfMissing = true)
  AuditEventPublisher directAuditEventPublisher(
      final InMemoryAuditEventPublisher chainPublisher, final AuditTransportProperties properties) {
    properties.validate();
    return chainPublisher;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableKafka
  @ConditionalOnProperty(prefix = "medassist.audit.transport", name = "mode", havingValue = "kafka")
  static class KafkaTransportConfiguration {
    @Bean
    FileAuditChainStore fileAuditChainStore(final AuditTransportProperties properties) {
      properties.validate();
      final AuditTransportProperties.Chain chain = properties.getChain();
      return new FileAuditChainStore(
          chain.getDirectory(), chain.getFile(), chain.getMaxRecordBytes());
    }

    @Bean
    DurableAuditEventBuffer durableAuditEventBuffer(
        final AuditTransportProperties properties, final ObjectMapper objectMapper) {
      properties.validate();
      final AuditTransportProperties.Buffer settings = properties.getBuffer();
      if (settings.getMode() == AuditTransportProperties.BufferMode.IN_MEMORY) {
        return new InMemoryBoundedAuditEventBuffer(settings.getCapacity());
      }
      return new FileDurableAuditEventBuffer(
          settings.getDirectory(),
          settings.getCapacity(),
          settings.getMaxMessageBytes(),
          objectMapper);
    }

    @Bean(name = "auditEventPublisher")
    @Primary
    KafkaAuditEventPublisher kafkaAuditEventPublisher(
        final KafkaTemplate<String, byte[]> kafkaTemplate,
        final AuditTransportProperties properties,
        final AuditEventCodec codec,
        final AuditEventValidator validator,
        final DurableAuditEventBuffer buffer,
        final AuditTransportMetrics metrics,
        final OpenTelemetry openTelemetry) {
      return new KafkaAuditEventPublisher(
          kafkaTemplate,
          properties.getTopic(),
          codec,
          validator,
          buffer,
          metrics,
          Duration.ofMillis(properties.getBuffer().getSendTimeoutMs()),
          openTelemetry);
    }

    @Bean
    KafkaAuditEventConsumer kafkaAuditEventConsumer(
        final AuditEventCodec codec,
        final AuditEventProcessor processor,
        final AuditTransportMetrics metrics,
        final OpenTelemetry openTelemetry) {
      return new KafkaAuditEventConsumer(codec, processor, metrics, openTelemetry);
    }

    @Bean
    DefaultErrorHandler auditKafkaErrorHandler(
        final KafkaTemplate<String, byte[]> kafkaTemplate,
        final AuditTransportProperties properties,
        final AuditTransportMetrics metrics) {
      final DeadLetterPublishingRecoverer publisher =
          new DeadLetterPublishingRecoverer(
              kafkaTemplate,
              (record, exception) -> new TopicPartition(properties.getDlqTopic(), 0));
      publisher.setFailIfSendResultIsError(true);
      publisher.setWaitForSendResultTimeout(
          Duration.ofMillis(properties.getBuffer().getSendTimeoutMs()));
      final ConsumerRecordRecoverer recoverer =
          new MeteredAuditDeadLetterRecoverer(publisher, metrics);
      final DefaultErrorHandler errorHandler =
          new DefaultErrorHandler(
              recoverer,
              new FixedBackOff(properties.getRetryDelayMs(), properties.getRetryAttempts()));
      errorHandler.setCommitRecovered(true);
      return errorHandler;
    }

    @Bean(name = "auditKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, byte[]> auditKafkaListenerContainerFactory(
        final ConsumerFactory<String, byte[]> consumerFactory,
        @Qualifier("auditKafkaErrorHandler") final DefaultErrorHandler errorHandler) {
      final ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
          new ConcurrentKafkaListenerContainerFactory<>();
      factory.setConsumerFactory(consumerFactory);
      factory.setConcurrency(1);
      factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      factory.setCommonErrorHandler(errorHandler);
      return factory;
    }
  }
}
