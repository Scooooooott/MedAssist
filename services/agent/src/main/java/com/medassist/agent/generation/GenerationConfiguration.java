package com.medassist.agent.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.application.AgentEntryService;
import com.medassist.common.context.ExecutorFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(GenerationProperties.class)
public class GenerationConfiguration {
  @Bean
  GenerationStore generationStore(
      final StringRedisTemplate redis,
      final ObjectProvider<ObjectMapper> objectMapperProvider,
      final GenerationProperties properties) {
    return new RedisGenerationStore(
        redis,
        objectMapperProvider.getIfAvailable(ObjectMapper::new),
        properties.keyPrefix(),
        properties.expiredMetadataRetention());
  }

  @Bean(name = "generationExecutor", destroyMethod = "shutdown")
  ExecutorService generationExecutor() {
    return ExecutorFactory.newVirtualThreadPerTaskExecutor();
  }

  @Bean(name = "generationStreamExecutor", destroyMethod = "shutdown")
  ExecutorService generationStreamExecutor() {
    return ExecutorFactory.newVirtualThreadPerTaskExecutor();
  }

  @Bean(name = "generationScheduler", destroyMethod = "shutdown")
  ScheduledExecutorService generationScheduler() {
    return ExecutorFactory.newSingleThreadScheduledExecutor("generation-deadline");
  }

  @Bean
  GenerationSessionService generationSessionService(
      final AgentEntryService entryService,
      final GenerationStore store,
      final GenerationProperties properties,
      final MeterRegistry meterRegistry,
      final ObjectProvider<Tracer> tracerProvider,
      @Qualifier("generationExecutor") final ExecutorService generationExecutor,
      @Qualifier("generationStreamExecutor") final ExecutorService streamExecutor,
      @Qualifier("generationScheduler") final ScheduledExecutorService scheduler,
      final ObjectProvider<ObjectMapper> objectMapperProvider,
      final ObjectProvider<Clock> clockProvider) {
    return new GenerationSessionService(
        entryService,
        store,
        properties,
        new GenerationOutputApprover(properties.maxChunkCharacters()),
        new GenerationPolicyGuard(properties.policyVersion()),
        new GenerationMetrics(meterRegistry),
        new GenerationTracing(tracerProvider.getIfAvailable()),
        generationExecutor,
        streamExecutor,
        scheduler,
        objectMapperProvider.getIfAvailable(ObjectMapper::new),
        clockProvider.getIfAvailable(Clock::systemUTC));
  }
}
