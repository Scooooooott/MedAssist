package com.medassist.gateway.ratelimit;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.medassist.gateway.config.GatewayRateLimitProperties;
import com.medassist.gateway.config.GatewayRateLimitProperties.Policy;
import com.medassist.gateway.http.ProblemResponseWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.HexFormat;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component
public final class RateLimitGlobalFilter implements GlobalFilter, Ordered {
  private final GatewayRateLimitProperties properties;
  private final RateLimitStore store;
  private final ProblemResponseWriter problems;
  private final List<PathPattern> llmPaths;

  public RateLimitGlobalFilter(
      final GatewayRateLimitProperties properties,
      final RateLimitStore store,
      final ProblemResponseWriter problems) {
    this.properties = properties;
    this.store = store;
    this.problems = problems;
    this.llmPaths =
        properties.llmPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
    if (!properties.enabled() || excluded(exchange)) {
      return chain.filter(exchange);
    }

    return exchange
        .getPrincipal()
        .map(Principal::getName)
        .flatMap(
            subject ->
                store
                    .consume(buckets(exchange, subject))
                    .onErrorReturn(RateLimitDecision.unavailable()))
        .switchIfEmpty(Mono.just(RateLimitDecision.unavailable()))
        .flatMap(decision -> applyDecision(exchange, chain, decision));
  }

  private Mono<Void> applyDecision(
      final ServerWebExchange exchange,
      final GatewayFilterChain chain,
      final RateLimitDecision decision) {
    if (decision.status() == RateLimitDecision.Status.ALLOWED) {
      return chain.filter(exchange);
    }
    if (decision.status() == RateLimitDecision.Status.REJECTED) {
      final long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
      exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
      return problems.write(
          exchange,
          HttpStatus.TOO_MANY_REQUESTS,
          "rate-limit-exceeded",
          "Too Many Requests",
          "The request rate exceeds the configured gateway policy.");
    }
    return problems.write(
        exchange,
        HttpStatus.SERVICE_UNAVAILABLE,
        "rate-limit-unavailable",
        "Service Unavailable",
        "The gateway cannot safely evaluate the request rate.");
  }

  private List<RateLimitBucket> buckets(final ServerWebExchange exchange, final String subject) {
    final Policy policy = policy(exchange);
    final String prefix = properties.keyPrefix();
    final String endpoint = routeId(exchange);
    return List.of(
        new RateLimitBucket(
            prefix + ":user:" + hash(subject), policy.userCapacity(), policy.window()),
        new RateLimitBucket(
            prefix + ":ip:" + hash(remoteAddress(exchange)), policy.ipCapacity(), policy.window()),
        new RateLimitBucket(
            prefix + ":endpoint:" + endpoint, policy.endpointCapacity(), policy.window()));
  }

  private Policy policy(final ServerWebExchange exchange) {
    final PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
    return llmPaths.stream().anyMatch(pattern -> pattern.matches(path))
        ? properties.llmPolicy()
        : properties.defaultPolicy();
  }

  private static boolean excluded(final ServerWebExchange exchange) {
    return exchange.getRequest().getMethod() == HttpMethod.OPTIONS
        || exchange.getRequest().getPath().value().startsWith("/actuator/health");
  }

  private static String routeId(final ServerWebExchange exchange) {
    final Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    return route == null ? "unmatched" : route.getId();
  }

  private static String remoteAddress(final ServerWebExchange exchange) {
    final InetSocketAddress address = exchange.getRequest().getRemoteAddress();
    return address == null ? "unknown" : address.getAddress().getHostAddress();
  }

  private static String hash(final String value) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @Override
  public int getOrder() {
    return -1;
  }
}
