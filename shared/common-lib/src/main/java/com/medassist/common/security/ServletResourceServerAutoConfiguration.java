package com.medassist.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.domain.Role;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration
@ConditionalOnClass({HttpSecurity.class, BearerTokenAuthenticationFilter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    prefix = "medassist.security",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(MedAssistSecurityProperties.class)
public class ServletResourceServerAutoConfiguration {
  private static final Set<String> DOMAIN_ROLES =
      Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  @Bean
  @ConditionalOnMissingBean
  JwtDecoder medAssistJwtDecoder(final MedAssistSecurityProperties properties) {
    if (properties.getJwkSetUri().isBlank()) {
      return token -> {
        throw new JwtException("JWT verification is not configured");
      };
    }
    final NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
    decoder.setJwtValidator(jwtValidator(properties));
    return decoder;
  }

  @Bean
  @ConditionalOnMissingBean
  JwtExecutionContextFilter jwtExecutionContextFilter() {
    return new JwtExecutionContextFilter();
  }

  @Bean
  @ConditionalOnMissingBean
  SecurityFilterChain medAssistSecurityFilterChain(
      final HttpSecurity http,
      final JwtDecoder decoder,
      final JwtExecutionContextFilter contextFilter,
      final ObjectMapper objectMapper)
      throws Exception {
    final ProblemSecurityHandler problemHandler = new ProblemSecurityHandler(objectMapper);
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(jwtConverter()))
                    .authenticationEntryPoint(problemHandler)
                    .accessDeniedHandler(problemHandler))
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
        .addFilterAfter(contextFilter, BearerTokenAuthenticationFilter.class)
        .build();
  }

  Converter<Jwt, AbstractAuthenticationToken> jwtConverter() {
    return jwt -> {
      if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error("invalid_token", "the token has no subject", null));
      }
      final Set<String> roles = medAssistRoles(jwt);
      if (roles.size() != 1) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error(
                "invalid_token", "the token must resolve to exactly one MedAssist role", null));
      }
      return new JwtAuthenticationToken(jwt, authorities(roles), jwt.getSubject());
    };
  }

  OAuth2TokenValidator<Jwt> jwtValidator(final MedAssistSecurityProperties properties) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
        new JwtClaimValidator<List<String>>(
            "aud", audience -> audience != null && audience.contains(properties.getAudience())));
  }

  private Collection<GrantedAuthority> authorities(final Set<String> roles) {
    return roles.stream()
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
        .collect(Collectors.toUnmodifiableList());
  }

  private Set<String> medAssistRoles(final Jwt jwt) {
    final Set<String> roles = new LinkedHashSet<>();
    final Object realmAccess = jwt.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> values) {
      values.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .map(value -> value.toUpperCase(Locale.ROOT))
          .filter(DOMAIN_ROLES::contains)
          .forEach(roles::add);
    }
    return Set.copyOf(roles);
  }
}
