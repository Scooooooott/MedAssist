package com.medassist.retrieval.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.cache.RedisJsonCache.CacheEntry;
import com.medassist.retrieval.config.RetrievalProperties;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public final class AnswerResponseCache {
  private final RetrievalProperties properties;
  private final CacheKeyFactory keys;
  private final SingleFlight singleFlight;
  private final RedisJsonCache<AnswerResponse> cache;
  private final CacheMetrics metrics;

  public AnswerResponseCache(
      final RetrievalProperties properties,
      final CacheKeyFactory keys,
      final SingleFlight singleFlight,
      final StringRedisTemplate redis,
      final ObjectMapper objectMapper) {
    this(properties, keys, singleFlight, redis, objectMapper, CacheMetrics.defaultInstance());
  }

  @Autowired
  public AnswerResponseCache(
      final RetrievalProperties properties,
      final CacheKeyFactory keys,
      final SingleFlight singleFlight,
      final StringRedisTemplate redis,
      final ObjectMapper objectMapper,
      final CacheMetrics metrics) {
    this.properties = properties;
    this.keys = keys;
    this.singleFlight = singleFlight;
    this.metrics = metrics;
    this.cache =
        new RedisJsonCache<>(
            redis,
            objectMapper,
            AnswerResponse.class,
            properties.getCache().getAnswerTtl(),
            metrics);
  }

  public AnswerResponse getOrCompute(
      final AnswerRequest request, final Supplier<AnswerResponse> supplier) {
    if (!properties.getCache().isAnswerEnabled()) {
      return supplier.get();
    }
    final String key = keys.answerKey(properties.getCache().getKeyPrefix(), request);
    final Optional<AnswerResponse> cached = cache.get(key);
    if (cached.isPresent()) {
      return cached.orElseThrow();
    }
    return singleFlight
        .execute(
            "answer",
            key,
            () -> {
              final CacheEntry<AnswerResponse> secondCheck = cache.getEntry(key).orElse(null);
              if (secondCheck != null) {
                return secondCheck;
              }
              final long started = System.nanoTime();
              final AnswerResponse response = supplier.get();
              final long observedLoadNanos = Math.max(1, System.nanoTime() - started);
              cache.put(key, response, observedLoadNanos);
              return new CacheEntry<>(response, observedLoadNanos);
            },
            entry -> {
              metrics.hit("answer");
              metrics.savedLatency("answer", entry.observedLoadNanos());
            })
        .value();
  }

  public void clear() {
    cache.clearPrefix(properties.getCache().getKeyPrefix() + "answer:");
  }
}
