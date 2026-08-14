package com.medassist.common.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class DegradationObservabilityAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(DegradationTrajectorySink.class)
  DegradationTrajectorySink degradationTrajectorySink() {
    return new BoundedDegradationTrajectorySink(10_000);
  }

  @Bean
  @ConditionalOnMissingBean(DegradationRecorder.class)
  DegradationRecorder degradationRecorder(
      final MeterRegistry meterRegistry,
      final ObjectProvider<Tracer> tracer,
      final ObjectProvider<DegradationAuditSink> auditSink,
      final ObjectProvider<DegradationTrajectorySink> trajectorySink) {
    return new ObservedDegradationRecorder(
        meterRegistry,
        tracer.getIfAvailable(),
        auditSink.getIfAvailable(),
        trajectorySink.getIfAvailable());
  }
}
