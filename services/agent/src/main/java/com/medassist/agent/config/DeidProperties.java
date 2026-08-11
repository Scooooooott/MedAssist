package com.medassist.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.deid")
public record DeidProperties(boolean enabled, String endpoint, Duration timeout, String policy) {
  public DeidProperties {
    if (enabled && (endpoint == null || endpoint.isBlank())) {
      throw new IllegalArgumentException("agent.deid.endpoint is required when enabled");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("agent.deid.timeout must be positive");
    }
    if (policy == null || policy.isBlank()) {
      throw new IllegalArgumentException("agent.deid.policy is required");
    }
  }
}
