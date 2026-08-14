package com.medassist.gateway.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.medassist.gateway.config.GatewayRateLimitProperties;
import com.medassist.gateway.config.GatewayRateLimitProperties.Policy;
import com.medassist.gateway.http.ProblemResponseWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RateLimitGlobalFilterTest {

  @Test
  void rejectedRequestReturnsProblemAndRetryAfter() {
    final AtomicReference<List<RateLimitBucket>> capturedBuckets = new AtomicReference<>();
    final RateLimitStore store =
        buckets -> {
          capturedBuckets.set(buckets);
          return Mono.just(RateLimitDecision.rejected(Duration.ofMillis(6500)));
        };
    final RateLimitGlobalFilter filter =
        new RateLimitGlobalFilter(properties(), store, new ProblemResponseWriter());
    final MockServerHttpRequest request =
        MockServerHttpRequest.post("/api/generations")
            .remoteAddress(new InetSocketAddress("192.0.2.10", 4321))
            .build();
    final MockServerWebExchange exchange =
        MockServerWebExchange.builder(request).principal((Principal) () -> "subject-1").build();
    exchange
        .getAttributes()
        .put(
            GATEWAY_ROUTE_ATTR,
            Route.async()
                .id("generation-sessions")
                .uri("http://localhost:8085")
                .predicate(ignored -> true)
                .build());
    final AtomicBoolean chainCalled = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              chainCalled.set(true);
              return Mono.empty();
            })
        .block();

    assertFalse(chainCalled.get());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    assertEquals("7", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    assertTrue(responseBody(exchange).contains("\"code\":\"rate-limit-exceeded\""));
    assertEquals(3, capturedBuckets.get().size());
    assertTrue(
        capturedBuckets.get().stream().noneMatch(bucket -> bucket.key().contains("subject-1")));
    assertTrue(
        capturedBuckets.get().stream().noneMatch(bucket -> bucket.key().contains("192.0.2.10")));
    assertEquals(10, capturedBuckets.get().getFirst().capacity());
  }

  @Test
  void unavailableRedisFailsClosed() {
    final RateLimitStore store =
        ignored -> Mono.error(new IllegalStateException("Redis is unavailable"));
    final RateLimitGlobalFilter filter =
        new RateLimitGlobalFilter(properties(), store, new ProblemResponseWriter());
    final MockServerWebExchange exchange =
        authenticatedExchange(MockServerHttpRequest.get("/api/agent/answer").build());
    final AtomicBoolean chainCalled = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              chainCalled.set(true);
              return Mono.empty();
            })
        .block();

    assertFalse(chainCalled.get());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    assertTrue(responseBody(exchange).contains("\"code\":\"rate-limit-unavailable\""));
  }

  private static String responseBody(final MockServerWebExchange exchange) {
    return DataBufferUtils.join(exchange.getResponse().getBody())
        .map(buffer -> StandardCharsets.UTF_8.decode(buffer.asByteBuffer()).toString())
        .block();
  }

  private static GatewayRateLimitProperties properties() {
    return new GatewayRateLimitProperties(
        true,
        "test:rate",
        new Policy(120, 240, 1000, Duration.ofMinutes(1)),
        new Policy(10, 20, 100, Duration.ofMinutes(1)),
        List.of("/api/agent/**", "/api/generations/**"));
  }

  private static MockServerWebExchange authenticatedExchange(final MockServerHttpRequest request) {
    final MockServerWebExchange exchange =
        MockServerWebExchange.builder(request).principal((Principal) () -> "subject-1").build();
    exchange
        .getAttributes()
        .put(
            GATEWAY_ROUTE_ATTR,
            Route.async()
                .id("agent")
                .uri("http://localhost:8085")
                .predicate(ignored -> true)
                .build());
    return exchange;
  }
}
