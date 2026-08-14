package com.medassist.gateway.http;

import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
public final class GatewayErrorWebExceptionHandler implements WebExceptionHandler, Ordered {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);
  private final ProblemResponseWriter problems;

  public GatewayErrorWebExceptionHandler(final ProblemResponseWriter problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> handle(final ServerWebExchange exchange, final Throwable failure) {
    if (causedBy(failure, AuthenticationException.class) || causedBy(failure, JwtException.class)) {
      return problems.write(
          exchange,
          HttpStatus.UNAUTHORIZED,
          "unauthorized",
          "Unauthorized",
          "A valid bearer token is required.");
    }
    LOGGER.warn("gateway_error type={}", failure.getClass().getName());
    if (failure instanceof DataBufferLimitException) {
      return problems.write(
          exchange,
          HttpStatus.PAYLOAD_TOO_LARGE,
          "payload-too-large",
          "Payload Too Large",
          "The request exceeds the configured gateway limit.");
    }
    if (failure instanceof TimeoutException) {
      return problems.write(
          exchange,
          HttpStatus.GATEWAY_TIMEOUT,
          "gateway-timeout",
          "Gateway Timeout",
          "The downstream service did not respond in time.");
    }
    if (failure instanceof ResponseStatusException statusFailure) {
      final HttpStatus status = HttpStatus.valueOf(statusFailure.getStatusCode().value());
      return problems.write(
          exchange,
          status,
          "gateway-" + status.value(),
          status.getReasonPhrase(),
          "The gateway could not complete the request.");
    }
    return problems.write(
        exchange,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "Internal Server Error",
        "The gateway could not complete the request.");
  }

  private static boolean causedBy(final Throwable failure, final Class<?> type) {
    Throwable current = failure;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
