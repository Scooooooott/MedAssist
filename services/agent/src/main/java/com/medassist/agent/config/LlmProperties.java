package com.medassist.agent.config;

import com.medassist.agent.llm.LlmCallMetadata;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.llm")
public record LlmProperties(
    boolean enabled,
    String provider,
    String model,
    Duration timeout,
    double inputCostPer1kTokens,
    double outputCostPer1kTokens) {
  public LlmProperties {
    provider = provider == null || provider.isBlank() ? "unconfigured" : provider;
    model = model == null || model.isBlank() ? "unconfigured" : model;
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("agent.llm.timeout must be positive");
    }
    if (inputCostPer1kTokens < 0 || outputCostPer1kTokens < 0) {
      throw new IllegalArgumentException("LLM token costs must be non-negative");
    }
  }

  public LlmCallMetadata metadata() {
    return new LlmCallMetadata(provider, model, timeout);
  }
}
