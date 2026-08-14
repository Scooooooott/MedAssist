package com.medassist.common.resilience;

import com.medassist.common.context.ExecutorFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Creates one configured resilience runtime per application context. */
@AutoConfiguration
@ConditionalOnClass(ResilienceExecutor.class)
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(ComponentPolicyTable.class)
  ComponentPolicyTable componentPolicyTable(final ResilienceProperties properties) {
    return properties.policyTable();
  }

  @Bean(name = "resilienceTimeoutExecutor", destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "resilienceTimeoutExecutor")
  ExecutorService resilienceTimeoutExecutor() {
    return ExecutorFactory.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ResilienceExecutor.class)
  ResilienceExecutor resilienceExecutor(
      final ComponentPolicyTable policyTable,
      @Qualifier("resilienceTimeoutExecutor") final ExecutorService timeoutExecutor,
      final ObjectProvider<MeterRegistry> meterRegistry,
      final ObjectProvider<Tracer> tracer) {
    return new ResilienceExecutor(
        policyTable,
        timeoutExecutor,
        RetryableFailureClassifier.transportFailures(),
        meterRegistry.getIfAvailable(),
        tracer.getIfAvailable());
  }
}
