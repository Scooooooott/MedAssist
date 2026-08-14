package com.medassist.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.llm.LlmGateway;
import com.medassist.agent.llm.SpringAiLlmGateway;
import com.medassist.agent.llm.UnavailableLlmGateway;
import com.medassist.agent.llm.routing.InMemoryLlmBudgetLedger;
import com.medassist.agent.llm.routing.InMemoryProviderRateLimiter;
import com.medassist.agent.llm.routing.LlmGatewayMetrics;
import com.medassist.agent.llm.routing.OpenAiCompatibleProviderAdapter;
import com.medassist.agent.llm.routing.ProviderRoutedLlmGateway;
import com.medassist.agent.security.DefaultEgressGuard;
import com.medassist.common.context.ExecutorFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, LlmRoutingProperties.class})
public class LlmGatewayConfiguration {
  @Bean(name = "llmGatewayExecutor", destroyMethod = "shutdown")
  ExecutorService llmGatewayExecutor() {
    return ExecutorFactory.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  @ConditionalOnMissingBean(LlmGateway.class)
  LlmGateway llmGateway(
      final ObjectProvider<ChatClient.Builder> builderProvider,
      final LlmProperties properties,
      final LlmRoutingProperties routing,
      final ObjectProvider<ObjectMapper> objectMapperProvider,
      final MeterRegistry meterRegistry,
      final OpenTelemetry openTelemetry,
      @Qualifier("llmGatewayExecutor") final ExecutorService executor) {
    if (!properties.enabled()) {
      return new UnavailableLlmGateway();
    }
    if (routing.isEnabled()) {
      final ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
      final HttpClient httpClient =
          HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
      final var adapters =
          routing.definitions().stream()
              .map(
                  definition ->
                      new OpenAiCompatibleProviderAdapter(
                          definition, httpClient, objectMapper, openTelemetry))
              .map(com.medassist.agent.llm.routing.LlmProviderAdapter.class::cast)
              .toList();
      return new ProviderRoutedLlmGateway(
          adapters,
          routing.getRoute(),
          new DefaultEgressGuard(),
          new InMemoryLlmBudgetLedger(routing.budgetLimits()),
          new InMemoryProviderRateLimiter(),
          new LlmGatewayMetrics(meterRegistry),
          Clock.systemUTC(),
          routing.getMaxRetryAfter(),
          openTelemetry);
    }
    final ChatClient.Builder builder = builderProvider.getIfAvailable();
    if (builder == null) {
      return new UnavailableLlmGateway();
    }
    return new SpringAiLlmGateway(builder.build(), properties, new DefaultEgressGuard(), executor);
  }
}
