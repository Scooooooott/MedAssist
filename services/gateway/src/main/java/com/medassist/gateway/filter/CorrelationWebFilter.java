package com.medassist.gateway.filter;

import com.medassist.gateway.http.ProblemResponseWriter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationWebFilter implements WebFilter, Ordered {
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String TRACEPARENT_HEADER = "traceparent";

  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
  private static final Pattern TRACEPARENT =
      Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
  private static final Set<String> UNTRUSTED_IDENTITY_HEADERS =
      Set.of(
          "X-Authenticated-User",
          "X-Authenticated-Roles",
          "X-Auth-Request-User",
          "X-Auth-Request-Email",
          "X-Auth-Request-Groups",
          "X-User-Id",
          "X-User",
          "X-Actor-Id",
          "X-Principal",
          "X-Subject",
          "X-Role",
          "X-Roles",
          "X-Forwarded-User",
          "X-Forwarded-Email",
          "X-Forwarded-Roles",
          "X-Scope",
          "X-Scopes",
          "X-MedAssist-Subject",
          "X-MedAssist-Role",
          "X-MedAssist-Roles");

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final HttpHeaders inbound = exchange.getRequest().getHeaders();
    final String requestId = validRequestId(inbound.get(REQUEST_ID_HEADER));
    final String traceparent = validTraceparent(inbound.get(TRACEPARENT_HEADER));

    final ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  UNTRUSTED_IDENTITY_HEADERS.forEach(headers::remove);
                  headers.set(REQUEST_ID_HEADER, requestId);
                  headers.set(TRACEPARENT_HEADER, traceparent);
                })
            .build();
    exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
    exchange.getResponse().getHeaders().set(TRACEPARENT_HEADER, traceparent);
    exchange.getAttributes().put(ProblemResponseWriter.REQUEST_ID_ATTRIBUTE, requestId);
    return chain.filter(exchange.mutate().request(request).build());
  }

  private static String validRequestId(final List<String> values) {
    if (values != null && values.size() == 1 && REQUEST_ID.matcher(values.getFirst()).matches()) {
      return values.getFirst();
    }
    return UUID.randomUUID().toString();
  }

  private static String validTraceparent(final List<String> values) {
    if (values != null && values.size() == 1 && TRACEPARENT.matcher(values.getFirst()).matches()) {
      return values.getFirst();
    }
    final String traceId = UUID.randomUUID().toString().replace("-", "");
    final String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
