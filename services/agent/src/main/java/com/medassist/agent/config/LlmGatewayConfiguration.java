package com.medassist.agent.config;

import com.medassist.agent.llm.LlmGateway;
import com.medassist.agent.llm.SpringAiLlmGateway;
import com.medassist.agent.llm.UnavailableLlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmGatewayConfiguration {
  @Bean
  @ConditionalOnMissingBean(LlmGateway.class)
  @ConditionalOnProperty(
      prefix = "agent.llm",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  LlmGateway unavailableLlmGateway() {
    return new UnavailableLlmGateway();
  }

  @Bean
  @ConditionalOnMissingBean(LlmGateway.class)
  @ConditionalOnProperty(prefix = "agent.llm", name = "enabled", havingValue = "true")
  LlmGateway springAiLlmGateway(
      final ObjectProvider<ChatClient.Builder> builderProvider, final LlmProperties properties) {
    final ChatClient.Builder builder = builderProvider.getIfAvailable();
    if (builder == null) {
      return new UnavailableLlmGateway();
    }
    return new SpringAiLlmGateway(builder.build(), properties);
  }
}
