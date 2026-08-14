package com.medassist.gateway.security;

import com.medassist.common.security.MedAssistSecurityProperties;
import com.medassist.gateway.config.GatewayCorsProperties;
import com.medassist.gateway.http.ProblemResponseWriter;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MedAssistSecurityProperties.class)
public class GatewaySecurityConfiguration {

  @Bean
  @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
  ReactiveJwtDecoder medAssistReactiveJwtDecoder(final MedAssistSecurityProperties properties) {
    if (properties.getJwkSetUri().isBlank()) {
      return token ->
          reactor.core.publisher.Mono.error(
              new org.springframework.security.oauth2.jwt.JwtException(
                  "JWT verification is not configured"));
    }
    final NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
    decoder.setJwtValidator(jwtValidator(properties));
    return decoder;
  }

  @Bean
  SecurityWebFilterChain gatewaySecurityFilterChain(
      final ServerHttpSecurity http, final ProblemResponseWriter problems) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(Customizer.withDefaults())
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .pathMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (exchange, ignored) ->
                            problems.write(
                                exchange,
                                HttpStatus.UNAUTHORIZED,
                                "unauthorized",
                                "Unauthorized",
                                "A valid bearer token is required."))
                    .accessDeniedHandler(
                        (exchange, ignored) ->
                            problems.write(
                                exchange,
                                HttpStatus.FORBIDDEN,
                                "forbidden",
                                "Forbidden",
                                "The authenticated subject is not allowed to access this resource.")))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(new GatewayJwtAuthenticationConverter()))
                    .authenticationEntryPoint(
                        (exchange, ignored) ->
                            problems.write(
                                exchange,
                                HttpStatus.UNAUTHORIZED,
                                "unauthorized",
                                "Unauthorized",
                                "A valid bearer token is required.")))
        .build();
  }

  @Bean
  CorsConfigurationSource gatewayCorsConfigurationSource(final GatewayCorsProperties properties) {
    final CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "Idempotency-Key",
            "Last-Event-ID",
            "X-Request-Id",
            "traceparent",
            "tracestate"));
    configuration.setExposedHeaders(
        List.of("X-Request-Id", "traceparent", HttpHeaders.RETRY_AFTER));
    configuration.setAllowCredentials(properties.allowCredentials());
    configuration.setMaxAge(properties.maxAge());

    final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private static OAuth2TokenValidator<Jwt> jwtValidator(
      final MedAssistSecurityProperties properties) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
        new JwtClaimValidator<List<String>>(
            "aud", audience -> audience != null && audience.contains(properties.getAudience())));
  }
}
