package com.medassist.gateway.filter;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.medassist.gateway.config.GatewayLoggingProperties;
import com.medassist.gateway.http.ProblemResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public final class SafeAccessLogGlobalFilter implements GlobalFilter, Ordered {
  private static final Logger LOGGER = LoggerFactory.getLogger(SafeAccessLogGlobalFilter.class);

  public SafeAccessLogGlobalFilter(final GatewayLoggingProperties ignored) {}

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
    return chain
        .filter(exchange)
        .doFinally(
            ignored ->
                LOGGER.info(
                    "gateway_request request_id={} method={} route={} status={}",
                    exchange.getAttributeOrDefault(
                        ProblemResponseWriter.REQUEST_ID_ATTRIBUTE, "unavailable"),
                    exchange.getRequest().getMethod(),
                    routeId(exchange),
                    status(exchange)));
  }

  private static String routeId(final ServerWebExchange exchange) {
    final Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    return route == null ? "unmatched" : route.getId();
  }

  private static int status(final ServerWebExchange exchange) {
    final HttpStatusCode status = exchange.getResponse().getStatusCode();
    return status == null ? 200 : status.value();
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
