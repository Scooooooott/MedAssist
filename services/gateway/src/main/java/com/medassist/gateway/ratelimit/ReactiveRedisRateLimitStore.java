package com.medassist.gateway.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class ReactiveRedisRateLimitStore implements RateLimitStore {
  private static final String CONSUME_SCRIPT =
      """
      local retry_after = 0
      for i = 1, #KEYS do
        local current = tonumber(redis.call('GET', KEYS[i]) or '0')
        local limit = tonumber(ARGV[((i - 1) * 2) + 1])
        local window = tonumber(ARGV[((i - 1) * 2) + 2])
        if current >= limit then
          local ttl = redis.call('PTTL', KEYS[i])
          if ttl < 1 then ttl = window end
          if ttl > retry_after then retry_after = ttl end
        end
      end
      if retry_after > 0 then return retry_after end
      for i = 1, #KEYS do
        local window = tonumber(ARGV[((i - 1) * 2) + 2])
        local current = redis.call('INCR', KEYS[i])
        if current == 1 then redis.call('PEXPIRE', KEYS[i], window) end
      end
      return -1
      """;

  private final ReactiveStringRedisTemplate redis;
  private final DefaultRedisScript<Long> script =
      new DefaultRedisScript<>(CONSUME_SCRIPT, Long.class);

  public ReactiveRedisRateLimitStore(final ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Mono<RateLimitDecision> consume(final List<RateLimitBucket> buckets) {
    if (buckets.isEmpty()) {
      return Mono.just(RateLimitDecision.allowed());
    }

    final List<String> keys = buckets.stream().map(RateLimitBucket::key).toList();
    final List<String> arguments = new ArrayList<>(buckets.size() * 2);
    for (RateLimitBucket bucket : buckets) {
      arguments.add(Long.toString(bucket.capacity()));
      arguments.add(Long.toString(bucket.window().toMillis()));
    }
    return redis
        .execute(script, keys, arguments.toArray())
        .next()
        .switchIfEmpty(Mono.error(new IllegalStateException("Redis returned no rate-limit result")))
        .map(
            retryAfterMillis ->
                retryAfterMillis < 0
                    ? RateLimitDecision.allowed()
                    : RateLimitDecision.rejected(Duration.ofMillis(retryAfterMillis)));
  }
}
