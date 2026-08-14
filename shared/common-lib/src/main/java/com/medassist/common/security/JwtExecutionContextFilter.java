package com.medassist.common.security;

import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds verified JWT identity to the request-scoped execution context and always clears it. */
public final class JwtExecutionContextFilter extends OncePerRequestFilter {
  public static final String REQUEST_ID_HEADER = "X-Request-ID";
  public static final String TRACEPARENT_HEADER = "traceparent";
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
  private static final Set<String> DOMAIN_ROLES = Set.of("CLINICIAN", "RESEARCHER", "ADMIN");

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token)
        || !authentication.isAuthenticated()) {
      filterChain.doFilter(request, response);
      return;
    }

    final String requestId = safeRequestId(request.getHeader(REQUEST_ID_HEADER));
    final String traceId =
        TraceParent.traceId(request.getHeader(TRACEPARENT_HEADER))
            .orElseGet(() -> TraceParent.traceId(TraceParent.create()).orElseThrow());
    final Set<String> roles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
            .map(value -> value.toUpperCase(java.util.Locale.ROOT))
            .filter(DOMAIN_ROLES::contains)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    final ExecutionContext context =
        new ExecutionContext(token.getToken().getSubject(), roles, requestId, traceId, Map.of());
    ContextCarrier.restore(context);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      ContextCarrier.clear();
    }
  }

  private String safeRequestId(final String value) {
    return value != null && SAFE_REQUEST_ID.matcher(value).matches()
        ? value
        : UUID.randomUUID().toString();
  }
}
