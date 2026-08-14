package com.medassist.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public final class ProblemResponseWriter {
  public static final String REQUEST_ID_ATTRIBUTE =
      ProblemResponseWriter.class.getName() + ".requestId";

  private static final String PROBLEM_BASE = "https://medassist.local/problems/";
  private final ObjectMapper objectMapper = new ObjectMapper();

  public Mono<Void> write(
      final ServerWebExchange exchange,
      final HttpStatus status,
      final String code,
      final String title,
      final String detail) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(new IllegalStateException("response is already committed"));
    }

    final String requestId =
        String.valueOf(exchange.getAttributeOrDefault(REQUEST_ID_ATTRIBUTE, "unavailable"));
    final Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", URI.create(PROBLEM_BASE + code));
    problem.put("title", title);
    problem.put("status", status.value());
    problem.put("detail", detail);
    problem.put("instance", URI.create("urn:medassist:request:" + requestId));
    problem.put("code", code);
    problem.put("request_id", requestId);

    final byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(problem);
    } catch (JsonProcessingException exception) {
      return Mono.error(new IllegalStateException("could not encode problem response", exception));
    }

    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    exchange.getResponse().getHeaders().setContentLength(body.length);
    return exchange
        .getResponse()
        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
  }
}
