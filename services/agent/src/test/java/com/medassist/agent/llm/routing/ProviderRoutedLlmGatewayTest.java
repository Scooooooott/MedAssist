package com.medassist.agent.llm.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.agent.llm.LlmCallMetadata;
import com.medassist.agent.llm.LlmCost;
import com.medassist.agent.llm.LlmFailureReason;
import com.medassist.agent.llm.LlmGatewayException;
import com.medassist.agent.llm.LlmRequest;
import com.medassist.agent.llm.LlmResponse;
import com.medassist.agent.llm.LlmUsage;
import com.medassist.agent.security.ContentClass;
import com.medassist.agent.security.DefaultEgressGuard;
import com.medassist.agent.security.EgressPolicy;
import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderRoutedLlmGatewayTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);

  @AfterEach
  void clearContext() {
    ContextCarrier.clear();
  }

  @Test
  void failoverRechecksEgressForTheNewDestination() {
    bindContext();
    final List<String> inspectedDestinations = new ArrayList<>();
    final var guard =
        new DefaultEgressGuard(
            new EgressPolicy(
                Set.of("LOCAL_MODEL", "EXTERNAL_LLM"),
                Set.of("LOCAL_MODEL"),
                Set.of(ContentClass.DEIDENTIFIED_QUERY)));
    final ProviderRoutedLlmGateway gateway =
        gateway(
            List.of(
                failing("local", "LOCAL_MODEL", LlmGatewayException.unavailable()),
                successful("external", "EXTERNAL_LLM")),
            request -> {
              inspectedDestinations.add(request.destination());
              return guard.inspect(request);
            },
            LlmBudgetLimits.defaults());

    assertThatThrownBy(() -> gateway.complete(new LlmRequest("system", "question")))
        .isInstanceOf(LlmGatewayException.class)
        .extracting(exception -> ((LlmGatewayException) exception).reason())
        .isEqualTo(LlmFailureReason.EGRESS_BLOCKED);

    assertThat(inspectedDestinations).contains("LOCAL_MODEL", "EXTERNAL_LLM");
  }

  @Test
  void providerFailureSwitchesWithoutChangingTheBusinessRequest() {
    bindContext();
    final ProviderRoutedLlmGateway gateway =
        gateway(
            List.of(
                failing("primary", "EXTERNAL_LLM", LlmGatewayException.providerError(null)),
                successful("secondary", "EXTERNAL_LLM")),
            new DefaultEgressGuard(),
            LlmBudgetLimits.defaults());

    final LlmResponse response = gateway.complete(new LlmRequest("system", "question"));

    assertThat(response.content()).isEqualTo("safe answer");
    assertThat(response.metadata().provider()).isEqualTo("secondary");
  }

  @Test
  void rateLimitResponseWaitsThenRetriesTheSameProvider() {
    bindContext();
    final AtomicInteger calls = new AtomicInteger();
    final LlmProviderDefinition definition = definition("primary", "EXTERNAL_LLM");
    final LlmProviderAdapter adapter =
        new LlmProviderAdapter() {
          @Override
          public LlmProviderDefinition definition() {
            return definition;
          }

          @Override
          public LlmResponse complete(final LlmRequest request) {
            if (calls.getAndIncrement() == 0) {
              throw LlmGatewayException.rateLimited(Duration.ofMillis(1));
            }
            return response(definition);
          }
        };
    final ProviderRoutedLlmGateway gateway =
        gateway(List.of(adapter), new DefaultEgressGuard(), LlmBudgetLimits.defaults());

    assertThat(gateway.complete(new LlmRequest("system", "question")).content())
        .isEqualTo("safe answer");
    assertThat(calls).hasValue(2);
  }

  @Test
  void hardBudgetRejectsBeforeCallingProvider() {
    bindContext();
    final AtomicInteger calls = new AtomicInteger();
    final LlmProviderAdapter provider = successful("primary", "EXTERNAL_LLM", calls);
    final LlmBudgetLimits tinyBudget =
        new LlmBudgetLimits(
            new BigDecimal("0.000001"),
            new BigDecimal("0.000001"),
            new BigDecimal("0.80"),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    assertThatThrownBy(
            () ->
                gateway(List.of(provider), new DefaultEgressGuard(), tinyBudget)
                    .complete(new LlmRequest("system", "question")))
        .isInstanceOf(LlmGatewayException.class)
        .extracting(exception -> ((LlmGatewayException) exception).reason())
        .isEqualTo(LlmFailureReason.BUDGET_EXCEEDED);
    assertThat(calls).hasValue(0);
  }

  @Test
  void movingModelAliasIsRejected() {
    assertThatThrownBy(
            () ->
                new LlmProviderDefinition(
                    "provider",
                    URI.create("https://example.invalid/chat"),
                    "",
                    "model-latest",
                    "EXTERNAL_LLM",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    10,
                    100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("immutable version");
  }

  @Test
  void retryAfterSupportsSecondsAndHttpDates() {
    final Instant now = Instant.parse("2026-08-11T10:00:00Z");

    assertThat(OpenAiCompatibleProviderAdapter.parseRetryAfterValue("7", now))
        .isEqualTo(Duration.ofSeconds(7));
    assertThat(
            OpenAiCompatibleProviderAdapter.parseRetryAfterValue(
                "Tue, 11 Aug 2026 10:00:09 GMT", now))
        .isEqualTo(Duration.ofSeconds(9));
    assertThat(OpenAiCompatibleProviderAdapter.parseRetryAfterValue("invalid", now))
        .isEqualTo(Duration.ofSeconds(1));
  }

  private static ProviderRoutedLlmGateway gateway(
      final List<LlmProviderAdapter> providers,
      final com.medassist.agent.security.EgressGuard guard,
      final LlmBudgetLimits limits) {
    return new ProviderRoutedLlmGateway(
        providers,
        providers.stream().map(provider -> provider.definition().id()).toList(),
        guard,
        new InMemoryLlmBudgetLedger(limits),
        new InMemoryProviderRateLimiter(),
        new LlmGatewayMetrics(new SimpleMeterRegistry()),
        CLOCK,
        Duration.ofMillis(5));
  }

  private static LlmProviderAdapter successful(final String id, final String destination) {
    return successful(id, destination, new AtomicInteger());
  }

  private static LlmProviderAdapter successful(
      final String id, final String destination, final AtomicInteger calls) {
    final LlmProviderDefinition definition = definition(id, destination);
    return adapter(
        definition,
        request -> {
          calls.incrementAndGet();
          return response(definition);
        });
  }

  private static LlmProviderAdapter failing(
      final String id, final String destination, final LlmGatewayException failure) {
    return adapter(
        definition(id, destination),
        request -> {
          throw failure;
        });
  }

  private static LlmProviderAdapter adapter(
      final LlmProviderDefinition definition,
      final java.util.function.Function<LlmRequest, LlmResponse> call) {
    return new LlmProviderAdapter() {
      @Override
      public LlmProviderDefinition definition() {
        return definition;
      }

      @Override
      public LlmResponse complete(final LlmRequest request) {
        return call.apply(request);
      }
    };
  }

  private static LlmProviderDefinition definition(final String id, final String destination) {
    return new LlmProviderDefinition(
        id,
        URI.create("https://example.invalid/chat"),
        "",
        id + "-v1",
        destination,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        100,
        100);
  }

  private static LlmResponse response(final LlmProviderDefinition definition) {
    final LlmCallMetadata metadata = definition.metadata();
    return new LlmResponse(
        "safe answer",
        metadata,
        new LlmUsage(10, 10, 20, metadata),
        new LlmCost(
            true,
            new BigDecimal("0.001"),
            new BigDecimal("0.002"),
            new BigDecimal("0.003"),
            "USD",
            metadata));
  }

  private static void bindContext() {
    ContextCarrier.restore(
        new ExecutionContext("user-1", Set.of("RESEARCHER"), "request-1", "trace-1", Map.of()));
  }
}
