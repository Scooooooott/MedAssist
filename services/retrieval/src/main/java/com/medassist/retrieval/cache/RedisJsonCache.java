package com.medassist.retrieval.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisJsonCache<T> {
  private static final int ENVELOPE_VERSION = 1;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Class<T> valueType;
  private final Duration ttl;
  private final CacheMetrics metrics;
  private final String metricScope;

  public RedisJsonCache(
      final StringRedisTemplate redis,
      final ObjectMapper objectMapper,
      final Class<T> valueType,
      final Duration ttl) {
    this(redis, objectMapper, valueType, ttl, CacheMetrics.defaultInstance());
  }

  public RedisJsonCache(
      final StringRedisTemplate redis,
      final ObjectMapper objectMapper,
      final Class<T> valueType,
      final Duration ttl,
      final CacheMetrics metrics) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.valueType = valueType;
    this.ttl = ttl;
    this.metrics = metrics;
    this.metricScope = metricScope(valueType);
  }

  public Optional<T> get(final String key) {
    return getEntry(key).map(CacheEntry::value);
  }

  public Optional<CacheEntry<T>> getEntry(final String key) {
    try {
      final String raw = redis.opsForValue().get(key);
      if (raw == null) {
        metrics.miss(metricScope);
        return Optional.empty();
      }
      final JsonNode envelope = objectMapper.readTree(raw);
      if (envelope.path("version").asInt(-1) != ENVELOPE_VERSION
          || !envelope.has("value")
          || !envelope.has("observedLoadNanos")) {
        metrics.miss(metricScope);
        return Optional.empty();
      }
      final long observedLoadNanos = envelope.path("observedLoadNanos").asLong(-1);
      if (observedLoadNanos <= 0) {
        metrics.miss(metricScope);
        return Optional.empty();
      }
      final T value = objectMapper.treeToValue(envelope.get("value"), valueType);
      metrics.hit(metricScope);
      metrics.savedLatency(metricScope, observedLoadNanos);
      refreshSizeForKey(key);
      return Optional.of(new CacheEntry<>(value, observedLoadNanos));
    } catch (final Exception ignored) {
      metrics.error(metricScope);
      return Optional.empty();
    }
  }

  public void put(final String key, final T value, final long observedLoadNanos) {
    if (observedLoadNanos <= 0) {
      return;
    }
    try {
      final ObjectNode envelope = objectMapper.createObjectNode();
      envelope.put("version", ENVELOPE_VERSION);
      envelope.put("observedLoadNanos", observedLoadNanos);
      envelope.set("value", objectMapper.valueToTree(value));
      redis.opsForValue().set(key, objectMapper.writeValueAsString(envelope), ttl);
      metrics.write(metricScope);
      refreshSizeForKey(key);
    } catch (final Exception ignored) {
      // Cache failure is intentionally fail-open.
      metrics.error(metricScope);
    }
  }

  public void clearPrefix(final String prefix) {
    try {
      final Set<String> keys = scan(prefix + "*");
      if (keys != null && !keys.isEmpty()) {
        redis.delete(keys);
      }
      metrics.size(metricScope, 0);
      metrics.invalidate(metricScope);
    } catch (final RuntimeException ignored) {
      // Cache invalidation is best-effort while Redis is unavailable.
      metrics.error(metricScope);
    }
  }

  private Set<String> scan(final String pattern) {
    return redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Set<String>>)
            connection -> {
              final Set<String> keys = new HashSet<>();
              try (Cursor<byte[]> cursor =
                  connection.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
                while (cursor.hasNext()) {
                  keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
              }
              return keys;
            });
  }

  private void refreshSizeForKey(final String key) {
    final String pattern = prefixPattern(key);
    if (pattern == null) {
      return;
    }
    try {
      final Set<String> keys = scan(pattern);
      metrics.size(metricScope, keys == null ? 0 : keys.size());
    } catch (final RuntimeException ignored) {
      metrics.error(metricScope);
    }
  }

  String prefixPattern(final String key) {
    final String marker = metricScope + ":";
    final int markerIndex = key.indexOf(marker);
    return markerIndex < 0 ? null : key.substring(0, markerIndex + marker.length()) + "*";
  }

  public record CacheEntry<T>(T value, long observedLoadNanos) {}

  private String metricScope(final Class<T> type) {
    final String simpleName = type.getSimpleName();
    if (simpleName.contains("QueryEmbedding")) {
      return "embedding";
    }
    if (simpleName.contains("AnswerResponse")) {
      return "answer";
    }
    return "other";
  }
}
