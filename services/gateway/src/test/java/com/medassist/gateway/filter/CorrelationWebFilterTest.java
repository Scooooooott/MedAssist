package com.medassist.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationWebFilterTest {
  private static final String VALID_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private final CorrelationWebFilter filter = new CorrelationWebFilter();

  @Test
  void removesSpoofedIdentityHeadersAndPreservesBearerToken() {
    final MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/generations/gen-1/events")
            .header(HttpHeaders.AUTHORIZATION, "Bearer signed-token")
            .header("X-Role", "ADMIN")
            .header("X-Roles", "ADMIN,CLINICIAN")
            .header("X-MedAssist-Subject", "spoofed-user")
            .header(CorrelationWebFilter.REQUEST_ID_HEADER, "request-123")
            .header(CorrelationWebFilter.TRACEPARENT_HEADER, VALID_TRACEPARENT)
            .build();
    final MockServerWebExchange exchange = MockServerWebExchange.from(request);
    final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    filter
        .filter(
            exchange,
            filtered -> {
              forwarded.set(filtered);
              return Mono.empty();
            })
        .block();

    final HttpHeaders headers = forwarded.get().getRequest().getHeaders();
    assertFalse(headers.containsHeader("X-Role"));
    assertFalse(headers.containsHeader("X-Roles"));
    assertFalse(headers.containsHeader("X-MedAssist-Subject"));
    assertEquals("Bearer signed-token", headers.getFirst(HttpHeaders.AUTHORIZATION));
    assertEquals("request-123", headers.getFirst(CorrelationWebFilter.REQUEST_ID_HEADER));
    assertEquals(VALID_TRACEPARENT, headers.getFirst(CorrelationWebFilter.TRACEPARENT_HEADER));
  }

  @Test
  void replacesInvalidOrAmbiguousCorrelationHeaders() {
    final MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/agent/answer")
            .header(CorrelationWebFilter.REQUEST_ID_HEADER, "first", "second")
            .header(CorrelationWebFilter.TRACEPARENT_HEADER, "not-a-traceparent")
            .build();
    final MockServerWebExchange exchange = MockServerWebExchange.from(request);
    final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    filter
        .filter(
            exchange,
            filtered -> {
              forwarded.set(filtered);
              return Mono.empty();
            })
        .block();

    final HttpHeaders headers = forwarded.get().getRequest().getHeaders();
    final String requestId = headers.getFirst(CorrelationWebFilter.REQUEST_ID_HEADER);
    final String traceparent = headers.getFirst(CorrelationWebFilter.TRACEPARENT_HEADER);
    assertNotEquals("first", requestId);
    assertTrue(requestId.matches("[A-Za-z0-9._-]{1,128}"));
    assertTrue(traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
    assertEquals(
        requestId,
        exchange.getResponse().getHeaders().getFirst(CorrelationWebFilter.REQUEST_ID_HEADER));
    assertEquals(
        traceparent,
        exchange.getResponse().getHeaders().getFirst(CorrelationWebFilter.TRACEPARENT_HEADER));
  }
}
