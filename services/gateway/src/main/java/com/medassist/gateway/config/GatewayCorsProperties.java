package com.medassist.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medassist.gateway.cors")
public record GatewayCorsProperties(
    List<String> allowedOrigins, boolean allowCredentials, Duration maxAge) {

  public GatewayCorsProperties {
    allowedOrigins = List.copyOf(allowedOrigins);
    if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("at least one non-blank CORS origin is required");
    }
    if (allowCredentials && allowedOrigins.contains("*")) {
      throw new IllegalArgumentException("credentialed CORS cannot allow every origin");
    }
    if (maxAge == null || maxAge.isNegative()) {
      throw new IllegalArgumentException("CORS max age must not be negative");
    }
  }
}
