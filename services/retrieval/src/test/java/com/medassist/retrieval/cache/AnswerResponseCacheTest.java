package com.medassist.retrieval.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.config.RetrievalProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AnswerResponseCacheTest {
  @Test
  void disabledCacheAlwaysUsesSupplier() {
    final RetrievalProperties properties = new RetrievalProperties();
    properties.getCache().setAnswerEnabled(false);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final AnswerResponseCache cache = cache(properties, redis, new SimpleMeterRegistry());
    final AtomicInteger calls = new AtomicInteger();

    cache.getOrCompute(request(), () -> response("first", calls.incrementAndGet()));
    final AnswerResponse second =
        cache.getOrCompute(request(), () -> response("second", calls.incrementAndGet()));

    assertEquals(2, calls.get());
    assertEquals("second", second.answer());
  }

  @Test
  void enabledCacheWritesOnceReusesAnswerAndCanBeCleared() {
    final RetrievalProperties properties = new RetrievalProperties();
    properties.getCache().setAnswerEnabled(true);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final AtomicReference<String> stored = new AtomicReference<>();
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenAnswer(ignored -> stored.get());
    doAnswer(
            invocation -> {
              stored.set(invocation.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AnswerResponseCache cache = cache(properties, redis, registry);
    final AtomicInteger calls = new AtomicInteger();

    final AnswerResponse first =
        cache.getOrCompute(request(), () -> response("grounded", calls.incrementAndGet()));
    final AnswerResponse second =
        cache.getOrCompute(request(), () -> response("unused", calls.incrementAndGet()));
    cache.clear();

    assertEquals(1, calls.get());
    assertEquals(first, second);
    verify(redis, atLeastOnce()).execute(any(RedisCallback.class));
    assertEquals(
        1.0, registry.find("medassist.cache.invalidate").tag("cache", "answer").counter().count());
    registry.close();
  }

  private AnswerResponseCache cache(
      final RetrievalProperties properties,
      final StringRedisTemplate redis,
      final SimpleMeterRegistry registry) {
    final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final CacheMetrics metrics = new CacheMetrics(registry);
    return new AnswerResponseCache(
        properties,
        new CacheKeyFactory(new QueryNormalizer()),
        new SingleFlight(metrics),
        redis,
        objectMapper,
        metrics);
  }

  private AnswerRequest request() {
    return new AnswerRequest("medical question", 5, null, "anonymous", "model", "v1");
  }

  private AnswerResponse response(final String answer, final int sequence) {
    return new AnswerResponse(
        "medical question",
        answer,
        List.of(),
        true,
        false,
        null,
        null,
        null,
        Instant.parse("2026-08-09T00:00:0" + sequence + "Z"));
  }
}
