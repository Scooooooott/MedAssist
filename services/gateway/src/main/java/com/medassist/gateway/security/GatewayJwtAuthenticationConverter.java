package com.medassist.gateway.security;

import com.medassist.domain.Role;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

public final class GatewayJwtAuthenticationConverter
    implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {
  private static final Set<String> DOMAIN_ROLES =
      Arrays.stream(Role.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

  @Override
  public Mono<AbstractAuthenticationToken> convert(final Jwt jwt) {
    final String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      return invalid("the token has no subject");
    }

    final Set<String> roles = extractRoles(jwt);
    if (roles.size() != 1) {
      return invalid("the token must resolve to exactly one MedAssist role");
    }

    final String role = roles.iterator().next();
    return Mono.just(
        new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), subject));
  }

  private static Set<String> extractRoles(final Jwt jwt) {
    final Set<String> roles = new LinkedHashSet<>();
    addRoles(roles, jwt.getClaim("roles"));
    final Object realmAccess = jwt.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> values) {
      addRoles(roles, values.get("roles"));
    }
    roles.retainAll(DOMAIN_ROLES);
    return Set.copyOf(roles);
  }

  private static void addRoles(final Set<String> target, final Object claim) {
    if (claim instanceof Collection<?> values) {
      values.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .map(value -> value.toUpperCase(Locale.ROOT))
          .filter(DOMAIN_ROLES::contains)
          .forEach(target::add);
    }
  }

  private static Mono<AbstractAuthenticationToken> invalid(final String description) {
    final OAuth2Error error = new OAuth2Error("invalid_token", description, null);
    return Mono.error(new OAuth2AuthenticationException(error));
  }
}
