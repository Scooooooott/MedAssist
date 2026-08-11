package com.medassist.retrieval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.retrieval.application.generation.AnswerGenerator;
import com.medassist.retrieval.application.generation.SpringAiAnswerGenerator;
import com.medassist.retrieval.application.generation.UnavailableAnswerGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class GenerationConfiguration {
  @Bean
  AnswerGenerator answerGenerator(
      final ObjectProvider<ChatClient.Builder> builderProvider,
      final ObjectMapper objectMapper,
      final ResourceLoader resourceLoader,
      final RetrievalProperties properties)
      throws IOException {
    if (!properties.getLlm().isEnabled()) {
      return new UnavailableAnswerGenerator();
    }
    final ChatClient.Builder builder = builderProvider.getIfAvailable();
    if (builder == null) {
      return new UnavailableAnswerGenerator();
    }
    final String prompt =
        resourceLoader
            .getResource(properties.getLlm().getSystemPrompt())
            .getContentAsString(StandardCharsets.UTF_8);
    return new SpringAiAnswerGenerator(
        builder.build(), objectMapper, prompt, properties.getLlmTimeout());
  }
}
