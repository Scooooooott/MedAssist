package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medassist.gateway.logging")
public record GatewayLoggingProperties(boolean requestBody, boolean responseBody) {

  public GatewayLoggingProperties {
    if (requestBody || responseBody) {
      throw new IllegalArgumentException(
          "gateway body logging is forbidden until an approved redacting logger exists");
    }
  }
}
