package com.medassist.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class GatewayJwtAuthenticationConverterTest {
  private final GatewayJwtAuthenticationConverter converter =
      new GatewayJwtAuthenticationConverter();

  @Test
  void mapsExactlyOneKnownRole() {
    final var authentication = converter.convert(jwt(List.of("CLINICIAN"))).block();

    assertEquals("subject-1", authentication.getName());
    assertEquals(
        "ROLE_CLINICIAN", authentication.getAuthorities().iterator().next().getAuthority());
  }

  @Test
  void rejectsMultipleKnownRoles() {
    assertThrows(
        OAuth2AuthenticationException.class,
        () -> converter.convert(jwt(List.of("CLINICIAN", "ADMIN"))).block());
  }

  private static Jwt jwt(final List<String> roles) {
    final Instant now = Instant.now();
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("subject-1")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60))
        .claim("roles", roles)
        .build();
  }
}
