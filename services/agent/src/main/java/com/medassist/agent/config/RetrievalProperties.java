package com.medassist.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.retrieval")
public record RetrievalProperties(boolean enabled, String endpoint, Duration timeout) {
  public RetrievalProperties {
    endpoint = endpoint == null || endpoint.isBlank() ? "localhost:9004" : endpoint;
    timeout = timeout == null ? Duration.ofMillis(500) : timeout;
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("agent.retrieval.timeout must be positive");
    }
  }
}
