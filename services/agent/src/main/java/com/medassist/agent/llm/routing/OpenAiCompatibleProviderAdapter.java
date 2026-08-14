package com.medassist.agent.llm.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.agent.llm.LlmCost;
import com.medassist.agent.llm.LlmGatewayException;
import com.medassist.agent.llm.LlmRequest;
import com.medassist.agent.llm.LlmResponse;
import com.medassist.agent.llm.LlmUsage;
import com.medassist.common.tracing.SafeTelemetryAttributes;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Absorbs OpenAI-compatible request, response, usage, and rate-limit conventions. */
public final class OpenAiCompatibleProviderAdapter implements LlmProviderAdapter {
  private final LlmProviderDefinition definition;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;
  private final TextMapPropagator propagator;

  public OpenAiCompatibleProviderAdapter(
      final LlmProviderDefinition definition,
      final HttpClient httpClient,
      final ObjectMapper objectMapper) {
    this(definition, httpClient, objectMapper, GlobalOpenTelemetry.get());
  }

  public OpenAiCompatibleProviderAdapter(
      final LlmProviderDefinition definition,
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final OpenTelemetry openTelemetry) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    final OpenTelemetry telemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
    tracer = telemetry.getTracer("com.medassist.agent.llm.http");
    propagator = telemetry.getPropagators().getTextMapPropagator();
  }

  @Override
  public LlmProviderDefinition definition() {
    return definition;
  }

  @Override
  public LlmResponse complete(final LlmRequest request) {
    Objects.requireNonNull(request, "request");
    final Span span =
        tracer.spanBuilder("llm.provider.call").setSpanKind(SpanKind.CLIENT).startSpan();
    SafeTelemetryAttributes.retainAllowed(
            Map.of(
                "llm.provider", definition.id(),
                "llm.destination", definition.destination(),
                "model.name", definition.model()))
        .forEach(span::setAttribute);
    try (Scope ignored = span.makeCurrent()) {
      final String body =
          objectMapper.writeValueAsString(
              Map.of(
                  "model",
                  definition.model(),
                  "messages",
                  List.of(
                      Map.of("role", "system", "content", request.systemPrompt()),
                      Map.of("role", "user", "content", request.userPrompt())),
                  "stream",
                  false,
                  "max_tokens",
                  definition.maxOutputTokens()));
      final HttpRequest.Builder builder =
          HttpRequest.newBuilder(definition.endpoint())
              .timeout(definition.overallTimeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (!definition.apiKey().isBlank()) {
        builder.header("Authorization", "Bearer " + definition.apiKey());
      }
      propagator.inject(
          Context.current(), builder, (carrier, key, value) -> carrier.header(key, value));
      final HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 429) {
        throw LlmGatewayException.rateLimited(parseRetryAfter(response, Instant.now()));
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw LlmGatewayException.providerError(null);
      }
      return mapResponse(response.body());
    } catch (final LlmGatewayException exception) {
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("request.reason_code", exception.reason().name());
      throw exception;
    } catch (final java.net.http.HttpTimeoutException exception) {
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("request.reason_code", "TIMEOUT");
      throw LlmGatewayException.timeout(exception);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("request.reason_code", "INTERRUPTED");
      throw LlmGatewayException.providerError(exception);
    } catch (final Exception exception) {
      span.setStatus(StatusCode.ERROR);
      throw LlmGatewayException.providerError(exception);
    } finally {
      span.end();
    }
  }

  private LlmResponse mapResponse(final String body) {
    try {
      final JsonNode root = objectMapper.readTree(body);
      final JsonNode content = root.path("choices").path(0).path("message").path("content");
      if (!content.isTextual() || content.asText().isBlank()) {
        throw LlmGatewayException.invalidResponse();
      }
      final JsonNode usageNode = root.path("usage");
      final long input = tokenValue(usageNode, "prompt_tokens");
      final long output = tokenValue(usageNode, "completion_tokens");
      final long total = tokenValue(usageNode, "total_tokens");
      final LlmUsage usage = new LlmUsage(input, output, total, definition.metadata());
      return new LlmResponse(content.asText(), definition.metadata(), usage, cost(usage));
    } catch (final LlmGatewayException exception) {
      throw exception;
    } catch (final Exception exception) {
      throw LlmGatewayException.invalidResponse();
    }
  }

  private LlmCost cost(final LlmUsage usage) {
    if (usage.inputTokens() < 0 || usage.outputTokens() < 0) {
      return LlmCost.unknown(definition.metadata());
    }
    final BigDecimal input =
        BigDecimal.valueOf(usage.inputTokens())
            .multiply(definition.inputCostPer1kTokens())
            .divide(BigDecimal.valueOf(1000));
    final BigDecimal output =
        BigDecimal.valueOf(usage.outputTokens())
            .multiply(definition.outputCostPer1kTokens())
            .divide(BigDecimal.valueOf(1000));
    return new LlmCost(true, input, output, input.add(output), "USD", definition.metadata());
  }

  private static long tokenValue(final JsonNode usage, final String field) {
    return usage.has(field) && usage.get(field).canConvertToLong()
        ? usage.get(field).asLong()
        : -1L;
  }

  static Duration parseRetryAfter(final HttpResponse<?> response, final Instant now) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(value -> parseRetryAfterValue(value, now))
        .orElse(Duration.ofSeconds(1));
  }

  static Duration parseRetryAfterValue(final String value, final Instant now) {
    Objects.requireNonNull(now, "now");
    if (value == null || value.isBlank()) {
      return Duration.ofSeconds(1);
    }
    try {
      return Duration.ofSeconds(Math.max(0L, Long.parseLong(value.trim())));
    } catch (final NumberFormatException ignored) {
      try {
        final Instant retryAt =
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        final Duration delay = Duration.between(now, retryAt);
        return delay.isNegative() ? Duration.ZERO : delay;
      } catch (final java.time.DateTimeException invalidDate) {
        return Duration.ofSeconds(1);
      }
    }
  }
}
