package com.medassist.retrieval.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.retrieval.cache.RedisJsonCache.CacheEntry;
import com.medassist.retrieval.config.RetrievalProperties;
import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.model.QueryEmbeddingClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CachingQueryEmbeddingClientTest {
  @Test
  void measuresDelegateLatencyAndReusesTheObservedBaseline() {
    final QueryEmbedding expected = new QueryEmbedding("bge-m3", "v1", List.of(0.1f), 1L);
    final QueryEmbeddingClient delegate =
        (query, modelName, modelVersion) -> {
          try {
            Thread.sleep(10);
          } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
          }
          return expected;
        };
    @SuppressWarnings("unchecked")
    final RedisJsonCache<QueryEmbedding> cache = mock(RedisJsonCache.class);
    final AtomicReference<CacheEntry<QueryEmbedding>> stored = new AtomicReference<>();
    when(cache.getEntry(anyString())).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    doAnswer(
            invocation -> {
              stored.set(new CacheEntry<>(invocation.getArgument(1), invocation.getArgument(2)));
              return null;
            })
        .when(cache)
        .put(
            anyString(),
            org.mockito.ArgumentMatchers.eq(expected),
            org.mockito.ArgumentMatchers.anyLong());
    final RetrievalProperties properties = new RetrievalProperties();
    properties.getCache().setEmbeddingEnabled(true);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CachingQueryEmbeddingClient client =
        new CachingQueryEmbeddingClient(
            delegate,
            cache,
            new CacheKeyFactory(new QueryNormalizer()),
            new SingleFlight(new CacheMetrics(registry)),
            properties,
            new CacheMetrics(registry));

    assertEquals(expected, client.embed("medical query", "bge-m3", "v1"));
    assertEquals(expected, client.embed("medical query", "bge-m3", "v1"));

    final ArgumentCaptor<Long> latency = ArgumentCaptor.forClass(Long.class);
    verify(cache).put(anyString(), org.mockito.ArgumentMatchers.eq(expected), latency.capture());
    assertTrue(latency.getValue() >= 1_000_000L);
    assertEquals(latency.getValue().longValue(), stored.get().observedLoadNanos());
    registry.close();
  }
}
