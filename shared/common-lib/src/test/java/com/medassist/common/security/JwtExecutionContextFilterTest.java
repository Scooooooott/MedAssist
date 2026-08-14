package com.medassist.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.common.context.ContextCarrier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtExecutionContextFilterTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    ContextCarrier.clear();
  }

  @Test
  void bindsOnlyVerifiedAuthenticationAndClearsAfterRequest() throws Exception {
    final Jwt jwt =
        new Jwt(
            "verified-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("sub", "subject-1"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_RESEARCHER"))));
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(JwtExecutionContextFilter.REQUEST_ID_HEADER, "request-1");
    request.addHeader(
        JwtExecutionContextFilter.TRACEPARENT_HEADER,
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final AtomicReference<String> observedRole = new AtomicReference<>();

    new JwtExecutionContextFilter()
        .doFilter(
            request,
            response,
            (ignoredRequest, ignoredResponse) ->
                observedRole.set(ContextCarrier.requireCurrent().roles().iterator().next()));

    assertEquals("RESEARCHER", observedRole.get());
    assertEquals("request-1", response.getHeader(JwtExecutionContextFilter.REQUEST_ID_HEADER));
    assertTrue(ContextCarrier.capture().isEmpty());
  }
}
