package com.medassist.retrieval.cache;

import com.medassist.retrieval.cache.RedisJsonCache.CacheEntry;
import com.medassist.retrieval.config.RetrievalProperties;
import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.model.QueryEmbeddingClient;

public final class CachingQueryEmbeddingClient implements QueryEmbeddingClient {
  private final QueryEmbeddingClient delegate;
  private final RedisJsonCache<QueryEmbedding> cache;
  private final CacheKeyFactory keys;
  private final SingleFlight singleFlight;
  private final RetrievalProperties properties;
  private final CacheMetrics metrics;

  public CachingQueryEmbeddingClient(
      final QueryEmbeddingClient delegate,
      final RedisJsonCache<QueryEmbedding> cache,
      final CacheKeyFactory keys,
      final SingleFlight singleFlight,
      final RetrievalProperties properties) {
    this(delegate, cache, keys, singleFlight, properties, CacheMetrics.defaultInstance());
  }

  CachingQueryEmbeddingClient(
      final QueryEmbeddingClient delegate,
      final RedisJsonCache<QueryEmbedding> cache,
      final CacheKeyFactory keys,
      final SingleFlight singleFlight,
      final RetrievalProperties properties,
      final CacheMetrics metrics) {
    this.delegate = delegate;
    this.cache = cache;
    this.keys = keys;
    this.singleFlight = singleFlight;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Override
  public QueryEmbedding embed(
      final String query, final String modelName, final String modelVersion) {
    if (!properties.getCache().isEmbeddingEnabled()) {
      return delegate.embed(query, modelName, modelVersion);
    }
    final String key =
        keys.embeddingKey(properties.getCache().getKeyPrefix(), query, modelName, modelVersion);
    final CacheEntry<QueryEmbedding> cached = cache.getEntry(key).orElse(null);
    if (cached != null) {
      return cached.value();
    }
    return singleFlight
        .execute(
            "embedding",
            key,
            () -> {
              final CacheEntry<QueryEmbedding> secondCheck = cache.getEntry(key).orElse(null);
              if (secondCheck != null) {
                return secondCheck;
              }
              final long started = System.nanoTime();
              final QueryEmbedding value = delegate.embed(query, modelName, modelVersion);
              final long observedLoadNanos = Math.max(1, System.nanoTime() - started);
              cache.put(key, value, observedLoadNanos);
              return new CacheEntry<>(value, observedLoadNanos);
            },
            entry -> {
              metrics.hit("embedding");
              metrics.savedLatency("embedding", entry.observedLoadNanos());
            })
        .value();
  }

  public void clear() {
    cache.clearPrefix(properties.getCache().getKeyPrefix() + "embedding:");
  }
}
