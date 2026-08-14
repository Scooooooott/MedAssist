package com.medassist.retrieval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.context.ExecutorFactory;
import com.medassist.common.resilience.ResilienceExecutor;
import com.medassist.retrieval.cache.CacheKeyFactory;
import com.medassist.retrieval.cache.CacheMetrics;
import com.medassist.retrieval.cache.CachingQueryEmbeddingClient;
import com.medassist.retrieval.cache.RedisJsonCache;
import com.medassist.retrieval.cache.SingleFlight;
import com.medassist.retrieval.model.GrpcQueryEmbeddingClient;
import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.rerank.GrpcRerankClient;
import com.medassist.retrieval.rerank.RerankingService;
import java.util.concurrent.ExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RetrievalClientConfiguration {
  @Bean(destroyMethod = "close")
  GrpcQueryEmbeddingClient rawQueryEmbeddingClient(final RetrievalProperties properties) {
    return new GrpcQueryEmbeddingClient(properties.getModelService().getEndpoint());
  }

  @Bean
  RedisJsonCache<QueryEmbedding> queryEmbeddingCache(
      final StringRedisTemplate redis,
      final ObjectMapper objectMapper,
      final CacheMetrics metrics,
      final RetrievalProperties properties) {
    return new RedisJsonCache<>(
        redis,
        objectMapper,
        QueryEmbedding.class,
        properties.getCache().getEmbeddingTtl(),
        metrics);
  }

  @Bean
  @Primary
  CachingQueryEmbeddingClient queryEmbeddingClient(
      final GrpcQueryEmbeddingClient delegate,
      final RedisJsonCache<QueryEmbedding> cache,
      final CacheKeyFactory keys,
      final SingleFlight singleFlight,
      final RetrievalProperties properties) {
    return new CachingQueryEmbeddingClient(delegate, cache, keys, singleFlight, properties);
  }

  @Bean(destroyMethod = "close")
  GrpcRerankClient rerankClient(final RetrievalProperties properties) {
    return new GrpcRerankClient(properties.getModelService().getEndpoint());
  }

  @Bean
  RerankingService rerankingService(
      final GrpcRerankClient client, final ResilienceExecutor resilienceExecutor) {
    return new RerankingService(client, resilienceExecutor);
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService retrievalExecutor() {
    return ExecutorFactory.newVirtualThreadPerTaskExecutor();
  }
}
