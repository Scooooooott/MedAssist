package com.medassist.gateway.ratelimit;

import java.util.List;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface RateLimitStore {
  Mono<RateLimitDecision> consume(List<RateLimitBucket> buckets);
}
