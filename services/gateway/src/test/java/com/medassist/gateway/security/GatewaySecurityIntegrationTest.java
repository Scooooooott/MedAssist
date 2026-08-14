package com.medassist.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "medassist.gateway.rate-limit.enabled=false")
@Import(GatewaySecurityIntegrationTest.InvalidDecoderConfiguration.class)
class GatewaySecurityIntegrationTest {
  private final WebTestClient client;

  GatewaySecurityIntegrationTest(@Value("${local.server.port}") final int port) {
    this.client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void invalidTokenReturnsRfc9457Problem() {
    client
        .get()
        .uri("/api/agent/answer")
        .headers(headers -> headers.setBearerAuth("invalid-token"))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("https://medassist.local/problems/unauthorized")
        .jsonPath("$.status")
        .isEqualTo(401)
        .jsonPath("$.code")
        .isEqualTo("unauthorized")
        .jsonPath("$.request_id")
        .isNotEmpty();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class InvalidDecoderConfiguration {
    @Bean
    @Primary
    ReactiveJwtDecoder invalidJwtDecoder() {
      return token -> Mono.error(new BadJwtException("test decoder rejected token"));
    }
  }
}
