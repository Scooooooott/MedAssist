package com.medassist.auditclient.autoconfigure;

import com.medassist.auditclient.AuditEventPublisher;
import com.medassist.auditclient.kafka.AuditClientMetrics;
import com.medassist.auditclient.kafka.KafkaAuditEventPublisher;
import com.medassist.auditclient.outbox.AuditOutbox;
import com.medassist.auditclient.outbox.FileDurableAuditOutbox;
import com.medassist.auditclient.proto.AuditEventProtoCodec;
import com.medassist.auditclient.resilience.AuditClientDegradationSink;
import com.medassist.common.resilience.DegradationAuditSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Opt-in shared audit client wiring for Java services. */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(AuditClientProperties.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "medassist.audit.client", name = "enabled", havingValue = "true")
public class AuditClientAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  AuditEventProtoCodec auditEventProtoCodec() {
    return new AuditEventProtoCodec();
  }

  @Bean
  @ConditionalOnMissingBean(AuditOutbox.class)
  AuditOutbox auditOutbox(final AuditClientProperties properties) {
    properties.validate();
    return new FileDurableAuditOutbox(
        properties.getOutboxDirectory(),
        properties.getOutboxCapacity(),
        properties.getMaxMessageBytes());
  }

  @Bean
  @ConditionalOnMissingBean
  AuditClientMetrics auditClientMetrics(
      final MeterRegistry meterRegistry, final AuditOutbox outbox) {
    final AuditClientMetrics metrics = new AuditClientMetrics(meterRegistry);
    metrics.setDepth(outbox.size());
    return metrics;
  }

  @Bean("auditClientTextMapPropagator")
  @ConditionalOnMissingBean(name = "auditClientTextMapPropagator")
  TextMapPropagator auditClientTextMapPropagator(final OpenTelemetry openTelemetry) {
    return openTelemetry.getPropagators().getTextMapPropagator();
  }

  @Bean
  @ConditionalOnMissingBean(AuditEventPublisher.class)
  AuditEventPublisher auditEventPublisher(
      final KafkaTemplate<String, byte[]> kafkaTemplate,
      final AuditClientProperties properties,
      final AuditEventProtoCodec codec,
      final AuditOutbox outbox,
      final AuditClientMetrics metrics,
      final OpenTelemetry openTelemetry,
      @Qualifier("auditClientTextMapPropagator") final TextMapPropagator propagator) {
    properties.validate();
    return new KafkaAuditEventPublisher(
        kafkaTemplate,
        properties.getTopic(),
        codec,
        outbox,
        metrics,
        openTelemetry,
        propagator,
        properties.getSendTimeout());
  }

  @Bean
  @ConditionalOnBean(AuditEventPublisher.class)
  @ConditionalOnMissingBean(DegradationAuditSink.class)
  DegradationAuditSink degradationAuditSink(final AuditEventPublisher publisher) {
    return new AuditClientDegradationSink(publisher);
  }
}
