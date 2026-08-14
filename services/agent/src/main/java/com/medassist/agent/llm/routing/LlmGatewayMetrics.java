package com.medassist.agent.llm.routing;

import com.medassist.agent.llm.LlmResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

public final class LlmGatewayMetrics {
  private final MeterRegistry registry;

  public LlmGatewayMetrics(final MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  public void success(final LlmResponse response, final boolean retry) {
    Counter.builder("medassist.llm.calls")
        .tag("provider", response.metadata().provider())
        .tag("model", response.metadata().model())
        .tag("outcome", "success")
        .tag("cache_hit", "false")
        .tag("retry", Boolean.toString(retry))
        .register(registry)
        .increment();
    if (response.cost().known()) {
      Counter.builder("medassist.llm.estimated.cost.usd")
          .tag("provider", response.metadata().provider())
          .tag("model", response.metadata().model())
          .register(registry)
          .increment(response.cost().totalCost().doubleValue());
    }
    if (response.usage().inputTokens() >= 0) {
      Counter.builder("medassist.llm.tokens")
          .tag("provider", response.metadata().provider())
          .tag("direction", "input")
          .register(registry)
          .increment(response.usage().inputTokens());
    }
    if (response.usage().outputTokens() >= 0) {
      Counter.builder("medassist.llm.tokens")
          .tag("provider", response.metadata().provider())
          .tag("direction", "output")
          .register(registry)
          .increment(response.usage().outputTokens());
    }
  }

  public void failure(final String provider, final String reason) {
    Counter.builder("medassist.llm.calls")
        .tag("provider", provider)
        .tag("model", "none")
        .tag("outcome", reason)
        .tag("cache_hit", "false")
        .tag("retry", "false")
        .register(registry)
        .increment();
  }

  public void softBudgetAlert() {
    registry.counter("medassist.llm.budget.soft_threshold").increment();
  }

  public void phiLeakageCanary() {
    registry.counter("medassist.phi.leakage.canary").increment();
  }
}
