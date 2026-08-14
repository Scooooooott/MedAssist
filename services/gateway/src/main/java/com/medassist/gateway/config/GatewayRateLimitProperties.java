package com.medassist.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medassist.gateway.rate-limit")
public record GatewayRateLimitProperties(
    boolean enabled,
    String keyPrefix,
    Policy defaultPolicy,
    Policy llmPolicy,
    List<String> llmPaths) {

  public GatewayRateLimitProperties {
    if (keyPrefix == null || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("rate-limit key prefix must not be blank");
    }
    if (defaultPolicy == null || llmPolicy == null) {
      throw new IllegalArgumentException("default and LLM rate-limit policies are required");
    }
    llmPaths = List.copyOf(llmPaths);
    if (llmPaths.isEmpty() || llmPaths.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("at least one LLM path is required");
    }
  }

  public record Policy(long userCapacity, long ipCapacity, long endpointCapacity, Duration window) {

    public Policy {
      if (userCapacity < 1 || ipCapacity < 1 || endpointCapacity < 1) {
        throw new IllegalArgumentException("rate-limit capacities must be positive");
      }
      if (window == null || window.isZero() || window.isNegative()) {
        throw new IllegalArgumentException("rate-limit window must be positive");
      }
    }
  }
}
