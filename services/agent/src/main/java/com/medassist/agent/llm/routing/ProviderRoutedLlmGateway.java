package com.medassist.agent.llm.routing;

import com.medassist.agent.llm.LlmFailureReason;
import com.medassist.agent.llm.LlmGateway;
import com.medassist.agent.llm.LlmGatewayException;
import com.medassist.agent.llm.LlmRequest;
import com.medassist.agent.llm.LlmResponse;
import com.medassist.agent.security.ContentClass;
import com.medassist.agent.security.EgressDecision;
import com.medassist.agent.security.EgressGuard;
import com.medassist.agent.security.EgressReason;
import com.medassist.agent.security.EgressRequest;
import com.medassist.agent.security.EgressSource;
import com.medassist.common.context.AuthenticatedRequestContext;
import com.medassist.common.context.ExecutionContext;
import com.medassist.common.tracing.SafeTelemetryAttributes;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Routes provider-neutral calls with budget, rate-limit, failover, and egress enforcement. */
public final class ProviderRoutedLlmGateway implements LlmGateway {
  private final Map<String, LlmProviderAdapter> providers;
  private final List<String> route;
  private final EgressGuard egressGuard;
  private final LlmBudgetLedger budgetLedger;
  private final ProviderRateLimiter rateLimiter;
  private final LlmGatewayMetrics metrics;
  private final Clock clock;
  private final Duration maxRetryAfter;
  private final Tracer tracer;

  public ProviderRoutedLlmGateway(
      final List<LlmProviderAdapter> providers,
      final List<String> route,
      final EgressGuard egressGuard,
      final LlmBudgetLedger budgetLedger,
      final ProviderRateLimiter rateLimiter,
      final LlmGatewayMetrics metrics,
      final Clock clock,
      final Duration maxRetryAfter) {
    this(
        providers,
        route,
        egressGuard,
        budgetLedger,
        rateLimiter,
        metrics,
        clock,
        maxRetryAfter,
        GlobalOpenTelemetry.get());
  }

  public ProviderRoutedLlmGateway(
      final List<LlmProviderAdapter> providers,
      final List<String> route,
      final EgressGuard egressGuard,
      final LlmBudgetLedger budgetLedger,
      final ProviderRateLimiter rateLimiter,
      final LlmGatewayMetrics metrics,
      final Clock clock,
      final Duration maxRetryAfter,
      final OpenTelemetry openTelemetry) {
    final Map<String, LlmProviderAdapter> indexed = new LinkedHashMap<>();
    for (final LlmProviderAdapter provider : providers) {
      final LlmProviderAdapter previous = indexed.put(provider.definition().id(), provider);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate LLM provider id");
      }
    }
    this.providers = Map.copyOf(indexed);
    this.route = List.copyOf(route);
    if (route.isEmpty() || route.stream().anyMatch(id -> !indexed.containsKey(id))) {
      throw new IllegalArgumentException("LLM route must reference configured providers");
    }
    this.egressGuard = Objects.requireNonNull(egressGuard, "egressGuard");
    this.budgetLedger = Objects.requireNonNull(budgetLedger, "budgetLedger");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maxRetryAfter = Objects.requireNonNull(maxRetryAfter, "maxRetryAfter");
    this.tracer =
        Objects.requireNonNull(openTelemetry, "openTelemetry").getTracer("com.medassist.agent.llm");
  }

  @Override
  public LlmResponse complete(final LlmRequest request) {
    Objects.requireNonNull(request, "request");
    final ExecutionContext context = AuthenticatedRequestContext.requireCurrent();
    for (final String providerId : route) {
      final LlmProviderAdapter provider = providers.get(providerId);
      try {
        return invoke(provider, request, context, false);
      } catch (final LlmGatewayException firstFailure) {
        metrics.failure(providerId, firstFailure.reason().name());
        if (firstFailure.reason() == LlmFailureReason.EGRESS_BLOCKED
            || firstFailure.reason() == LlmFailureReason.BUDGET_EXCEEDED) {
          throw firstFailure;
        }
        if (firstFailure.reason() == LlmFailureReason.RATE_LIMITED) {
          backoff(firstFailure.retryAfter());
          try {
            return invoke(provider, request, context, true);
          } catch (final LlmGatewayException retryFailure) {
            metrics.failure(providerId, retryFailure.reason().name());
            if (retryFailure.reason() == LlmFailureReason.EGRESS_BLOCKED
                || retryFailure.reason() == LlmFailureReason.BUDGET_EXCEEDED) {
              throw retryFailure;
            }
          }
        }
      }
    }
    throw LlmGatewayException.allProvidersUnavailable();
  }

  private LlmResponse invoke(
      final LlmProviderAdapter provider,
      final LlmRequest request,
      final ExecutionContext context,
      final boolean retry) {
    final LlmProviderDefinition definition = provider.definition();
    final Span span = tracer.spanBuilder("llm.generate").startSpan();
    SafeTelemetryAttributes.retainAllowed(
            Map.of(
                "llm.provider", definition.id(),
                "llm.destination", definition.destination(),
                "model.name", definition.model()))
        .forEach(span::setAttribute);
    try (Scope ignored = span.makeCurrent()) {
      enforceEgress(definition.destination(), request);
      final Instant now = clock.instant();
      if (!rateLimiter.tryAcquire(definition.id(), definition.requestsPerMinute(), now)) {
        throw LlmGatewayException.rateLimited(Duration.ofSeconds(1));
      }
      final int inputUtf8Bytes =
          request.systemPrompt().getBytes(StandardCharsets.UTF_8).length
              + request.userPrompt().getBytes(StandardCharsets.UTF_8).length;
      final BigDecimal estimate = definition.estimateCost(inputUtf8Bytes);
      final LlmBudgetLedger.Reservation reservation = budgetLedger.reserve(context, estimate, now);
      try {
        final LlmResponse response = provider.complete(request);
        final BigDecimal actual = response.cost().known() ? response.cost().totalCost() : estimate;
        budgetLedger.commit(reservation, actual, clock.instant());
        metrics.success(response, retry);
        if (budgetLedger.snapshot(context, clock.instant()).softThresholdReached()) {
          metrics.softBudgetAlert();
        }
        return response;
      } catch (final LlmGatewayException exception) {
        budgetLedger.release(reservation);
        throw exception;
      } catch (final RuntimeException exception) {
        budgetLedger.release(reservation);
        throw LlmGatewayException.providerError(exception);
      }
    } catch (final LlmGatewayException failure) {
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("request.reason_code", failure.reason().name());
      throw failure;
    } finally {
      span.end();
    }
  }

  private void enforceEgress(final String destination, final LlmRequest request) {
    final Span span = tracer.spanBuilder("egress.guard").startSpan();
    span.setAttribute("llm.destination", destination);
    try (Scope ignored = span.makeCurrent()) {
      requireAllowed(
          new EgressRequest(
              destination,
              ContentClass.DEIDENTIFIED_QUERY,
              EgressSource.SYSTEM_PROMPT,
              request.systemPrompt(),
              false));
      requireAllowed(
          new EgressRequest(
              destination,
              ContentClass.DEIDENTIFIED_QUERY,
              EgressSource.USER_QUERY,
              request.userPrompt(),
              false));
    } catch (final LlmGatewayException failure) {
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("request.reason_code", failure.reason().name());
      throw failure;
    } finally {
      span.end();
    }
  }

  private void requireAllowed(final EgressRequest request) {
    try {
      final EgressDecision decision = egressGuard.inspect(request);
      if (decision != null && decision.reason() == EgressReason.SENSITIVE_CONTENT) {
        metrics.phiLeakageCanary();
      }
      if (decision == null || !decision.allowed()) {
        throw LlmGatewayException.egressBlocked();
      }
    } catch (final LlmGatewayException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw LlmGatewayException.egressBlocked();
    }
  }

  private void backoff(final Duration requested) {
    final Duration requestedDelay = requested == null ? Duration.ofSeconds(1) : requested;
    final Duration delay =
        requestedDelay.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : requestedDelay;
    try {
      Thread.sleep(delay);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw LlmGatewayException.providerError(exception);
    }
  }
}
