package com.medassist.auditclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.medassist.auditclient.AuditEventPublisher;
import com.medassist.auditclient.kafka.AuditClientMetrics;
import com.medassist.auditclient.outbox.AuditOutbox;
import com.medassist.auditclient.outbox.FileDurableAuditOutbox;
import com.medassist.auditclient.proto.AuditEventProtoCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

class AuditClientAutoConfigurationTest {
  @TempDir Path directory;

  @Test
  void clientIsDisabledByDefault() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuditClientAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(AuditEventPublisher.class));
  }

  @Test
  void enabledClientProvidesDurablePublisherComponents() {
    configuredRunner()
        .withPropertyValues(
            "medassist.audit.client.enabled=true",
            "medassist.audit.client.topic=custom-audit-events",
            "medassist.audit.client.outbox-directory=" + directory,
            "medassist.audit.client.outbox-capacity=8",
            "medassist.audit.client.max-message-bytes=4096",
            "medassist.audit.client.drain-delay=1h",
            "medassist.audit.client.send-timeout=2s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AuditEventPublisher.class);
              assertThat(context).hasSingleBean(AuditEventProtoCodec.class);
              assertThat(context).hasSingleBean(AuditClientMetrics.class);
              assertThat(context).hasSingleBean(AuditOutbox.class);
              assertThat(context.getBean(AuditOutbox.class))
                  .isInstanceOf(FileDurableAuditOutbox.class);
            });
  }

  @Test
  void invalidEnabledConfigurationFailsStartup() {
    configuredRunner()
        .withPropertyValues(
            "medassist.audit.client.enabled=true",
            "medassist.audit.client.outbox-directory=" + directory,
            "medassist.audit.client.outbox-capacity=0")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class);
            });
  }

  private static ApplicationContextRunner configuredRunner() {
    final OpenTelemetry telemetry =
        OpenTelemetrySdk.builder()
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuditClientAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(OpenTelemetry.class, () -> telemetry)
        .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class));
  }
}
