package com.medassist.retrieval.config;

import com.medassist.retrieval.model.GrpcQueryEmbeddingClient;
import com.medassist.retrieval.model.QueryEmbeddingClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrievalClientConfiguration {
  @Bean(destroyMethod = "close")
  QueryEmbeddingClient queryEmbeddingClient(final RetrievalProperties properties) {
    return new GrpcQueryEmbeddingClient(properties.getModelService().getEndpoint());
  }
}
