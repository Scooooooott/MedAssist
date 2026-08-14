package com.medassist.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class ServletResourceServerAutoConfigurationTest {
  private final ServletResourceServerAutoConfiguration configuration =
      new ServletResourceServerAutoConfiguration();

  @Test
  void acceptsExactlyOneKnownRealmRole() {
    final AbstractAuthenticationToken authentication =
        configuration
            .jwtConverter()
            .convert(jwt("https://issuer.example", List.of("medassist"), List.of("clinician")));

    assertEquals(
        "ROLE_CLINICIAN", authentication.getAuthorities().iterator().next().getAuthority());
  }

  @Test
  void rejectsMissingRole() {
    assertThrows(
        OAuth2AuthenticationException.class,
        () ->
            configuration
                .jwtConverter()
                .convert(jwt("https://issuer.example", List.of("medassist"), null)));
  }

  @Test
  void rejectsMissingSubject() {
    final Instant now = Instant.now();
    final Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .claim("iss", "https://issuer.example")
            .claim("aud", List.of("medassist"))
            .claim("realm_access", Map.of("roles", List.of("CLINICIAN")))
            .build();

    assertThrows(
        OAuth2AuthenticationException.class, () -> configuration.jwtConverter().convert(jwt));
  }

  @Test
  void rejectsUnknownRole() {
    assertThrows(
        OAuth2AuthenticationException.class,
        () ->
            configuration
                .jwtConverter()
                .convert(jwt("https://issuer.example", List.of("medassist"), List.of("viewer"))));
  }

  @Test
  void rejectsMultipleKnownRoles() {
    assertThrows(
        OAuth2AuthenticationException.class,
        () ->
            configuration
                .jwtConverter()
                .convert(
                    jwt(
                        "https://issuer.example",
                        List.of("medassist"),
                        List.of("CLINICIAN", "ADMIN"))));
  }

  @Test
  void validatesIssuerAndAudience() {
    final MedAssistSecurityProperties properties = new MedAssistSecurityProperties();
    properties.setIssuer("https://issuer.example");
    properties.setAudience("medassist");
    final OAuth2TokenValidator<Jwt> validator = configuration.jwtValidator(properties);

    assertFalse(
        validator
            .validate(jwt("https://issuer.example", List.of("medassist"), List.of("ADMIN")))
            .hasErrors());
    assertTrue(
        validator
            .validate(jwt("https://other-issuer.example", List.of("medassist"), List.of("ADMIN")))
            .hasErrors());
    assertTrue(
        validator
            .validate(jwt("https://issuer.example", List.of("other"), List.of("ADMIN")))
            .hasErrors());
  }

  private static Jwt jwt(
      final String issuer, final List<String> audience, final List<String> roles) {
    final Instant now = Instant.now();
    final var builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("subject-1")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .claim("iss", issuer)
            .claim("aud", audience);
    if (roles != null) {
      builder.claim("realm_access", Map.of("roles", roles));
    }
    return builder.build();
  }
}
