package com.medassist.retrieval.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medassist.retrieval.model.QueryEmbedding;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisJsonCacheTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void recordsHitMissAndWriteForEmbeddingCache() throws Exception {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RedisJsonCache<QueryEmbedding> cache = cache(redis, values, registry);
    final QueryEmbedding expected =
        new QueryEmbedding("bge-m3", "v1", java.util.List.of(0.1f, 0.2f), 3L);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("hit")).thenReturn(envelope(expected, 2_000_000));
    when(values.get("miss")).thenReturn(null);

    assertEquals(expected, cache.get("hit").orElseThrow());
    assertTrue(cache.get("miss").isEmpty());
    cache.put("write", expected, 3_000_000);

    assertEquals(1.0, count(registry, "medassist.cache.hit", "embedding"));
    assertEquals(1.0, count(registry, "medassist.cache.miss", "embedding"));
    assertEquals(1.0, count(registry, "medassist.cache.write", "embedding"));
    assertEquals(
        1.0,
        registry.find("medassist.cache.saved.latency").tag("cache", "embedding").summary().count());
    registry.close();
  }

  @Test
  void redisFailuresFailOpenAndIncrementErrorMetric() {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RedisJsonCache<QueryEmbedding> cache = cache(redis, values, registry);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("fault")).thenThrow(new IllegalStateException("redis unavailable"));
    doThrow(new IllegalStateException("redis unavailable"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    when(redis.execute(any(RedisCallback.class)))
        .thenThrow(new IllegalStateException("redis unavailable"));

    assertTrue(cache.get("fault").isEmpty());
    cache.put("fault", new QueryEmbedding("bge-m3", "v1", java.util.List.of(0.1f), 1L), 1_000_000);
    cache.clearPrefix("medassist:");

    assertEquals(3.0, count(registry, "medassist.cache.error", "embedding"));
    verify(redis, never()).keys(anyString());
    registry.close();
  }

  @Test
  void oldUnversionedValueIsAFailOpenMiss() throws Exception {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RedisJsonCache<QueryEmbedding> cache = cache(redis, values, registry);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("old"))
        .thenReturn(
            objectMapper.writeValueAsString(
                new QueryEmbedding("bge-m3", "v1", java.util.List.of(0.1f), 1L)));

    assertTrue(cache.get("old").isEmpty());

    assertEquals(1.0, count(registry, "medassist.cache.miss", "embedding"));
    assertTrue(registry.find("medassist.cache.error").counter() == null);
    registry.close();
  }

  @Test
  void sizeGaugeScansOnlyTheMedAssistEmbeddingPrefix() throws Exception {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final RedisJsonCache<QueryEmbedding> cache = cache(redis, values, registry);
    final String key = "medassist:m2:embedding:hash";
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(key))
        .thenReturn(
            envelope(new QueryEmbedding("bge-m3", "v1", java.util.List.of(0.1f), 1L), 5_000_000));
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(Set.of("medassist:m2:embedding:a", "medassist:m2:embedding:b"));

    assertTrue(cache.get(key).isPresent());

    assertEquals("medassist:m2:embedding:*", cache.prefixPattern(key));
    assertEquals(
        2.0, registry.find("medassist.cache.size").tag("cache", "embedding").gauge().value());
    verify(redis, never()).keys(anyString());
    registry.close();
  }

  @Test
  void metricTagsAreFixedAndContainNoCacheKeyOrRequestData() {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CacheMetrics metrics = new CacheMetrics(registry);
    final RedisJsonCache<QueryEmbedding> cache = cache(redis, values, metrics);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("query:secret-role:clinician")).thenReturn(null);

    cache.get("query:secret-role:clinician");
    metrics.savedLatency("embedding", 2_000_000);
    metrics.size("embedding", 2);

    for (final Meter meter : registry.getMeters()) {
      if (meter.getId().getName().startsWith("medassist.cache.")) {
        assertEquals(
            Set.of("cache"),
            meter.getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals("embedding", meter.getId().getTag("cache"));
        assertTrue(
            meter.getId().getTags().stream().noneMatch(tag -> tag.getValue().contains("secret")));
      }
    }
    registry.close();
  }

  private RedisJsonCache<QueryEmbedding> cache(
      final StringRedisTemplate redis,
      final ValueOperations<String, String> values,
      final SimpleMeterRegistry registry) {
    return cache(redis, values, new CacheMetrics(registry));
  }

  private RedisJsonCache<QueryEmbedding> cache(
      final StringRedisTemplate redis,
      final ValueOperations<String, String> values,
      final CacheMetrics metrics) {
    return new RedisJsonCache<>(
        redis, objectMapper, QueryEmbedding.class, Duration.ofMinutes(5), metrics);
  }

  private double count(final SimpleMeterRegistry registry, final String name, final String cache) {
    return registry.find(name).tag("cache", cache).counter().count();
  }

  private String envelope(final QueryEmbedding value, final long observedLoadNanos)
      throws Exception {
    final ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("version", 1);
    envelope.put("observedLoadNanos", observedLoadNanos);
    envelope.set("value", objectMapper.valueToTree(value));
    return objectMapper.writeValueAsString(envelope);
  }
}
