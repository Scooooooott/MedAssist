package com.medassist.common.resilience;

import com.medassist.common.resilience.ComponentPolicy.BulkheadPolicy;
import com.medassist.common.resilience.ComponentPolicy.CircuitBreakerPolicy;
import com.medassist.common.resilience.ComponentPolicy.RateLimitPolicy;
import com.medassist.common.resilience.ComponentPolicy.RetryPolicy;
import com.medassist.common.resilience.ComponentPolicy.TimeoutPolicy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Sparse environment overrides layered on top of the conservative component policy table. */
@ConfigurationProperties("medassist.resilience")
public record ResilienceProperties(
    @DefaultValue("20") int retrievalPoolSize,
    @DefaultValue("8") int clinicalDataPoolSize,
    Map<String, ComponentOverrides> components) {

  public ResilienceProperties {
    if (retrievalPoolSize < 1 || clinicalDataPoolSize < 1) {
      throw new IllegalArgumentException("resilience pool sizes must be positive");
    }
    components = components == null ? Map.of() : Map.copyOf(components);
  }

  public ComponentPolicyTable policyTable() {
    final EnumMap<ResilienceComponent, ComponentPolicy> resolved =
        new EnumMap<>(ResilienceComponent.class);
    final EnumSet<ResilienceComponent> overridden = EnumSet.noneOf(ResilienceComponent.class);
    ComponentPolicyTable.conservativeDefaults()
        .policies()
        .forEach(policy -> resolved.put(policy.component(), policy));

    components.forEach(
        (configuredName, overrides) -> {
          final ResilienceComponent component = parseComponent(configuredName);
          if (!overridden.add(component)) {
            throw new IllegalArgumentException(
                "duplicate resilience component override: " + configuredName);
          }
          final ComponentPolicy baseline = resolved.get(component);
          resolved.put(
              component, merge(baseline, Objects.requireNonNull(overrides, "component overrides")));
        });
    validateOwnedCapacity(resolved);
    return ComponentPolicyTable.of(resolved.values());
  }

  private ComponentPolicy merge(
      final ComponentPolicy baseline, final ComponentOverrides overrides) {
    final CircuitBreakerOverrides circuit = overrides.circuitBreaker();
    final RetryOverrides retry = overrides.retry();
    final BulkheadOverrides bulkhead = overrides.bulkhead();
    final RateLimitOverrides rateLimit = overrides.rateLimit();
    return new ComponentPolicy(
        baseline.component(),
        circuit == null
            ? baseline.circuitBreaker()
            : new CircuitBreakerPolicy(
                value(circuit.enabled(), baseline.circuitBreaker().enabled()),
                value(
                    circuit.failureRateThreshold(),
                    baseline.circuitBreaker().failureRateThreshold()),
                value(circuit.slidingWindowSize(), baseline.circuitBreaker().slidingWindowSize()),
                value(
                    circuit.minimumNumberOfCalls(),
                    baseline.circuitBreaker().minimumNumberOfCalls()),
                value(
                    circuit.permittedCallsInHalfOpenState(),
                    baseline.circuitBreaker().permittedCallsInHalfOpenState()),
                value(circuit.openStateDuration(), baseline.circuitBreaker().openStateDuration())),
        retry == null
            ? baseline.retry()
            : new RetryPolicy(
                value(retry.enabled(), baseline.retry().enabled()),
                value(retry.maxAttempts(), baseline.retry().maxAttempts()),
                value(retry.waitDuration(), baseline.retry().waitDuration())),
        new TimeoutPolicy(value(overrides.timeout(), baseline.timeout().duration())),
        bulkhead == null
            ? baseline.bulkhead()
            : new BulkheadPolicy(
                value(bulkhead.enabled(), baseline.bulkhead().enabled()),
                value(bulkhead.maxConcurrentCalls(), baseline.bulkhead().maxConcurrentCalls()),
                value(bulkhead.maxWaitDuration(), baseline.bulkhead().maxWaitDuration())),
        rateLimit == null
            ? baseline.rateLimit()
            : new RateLimitPolicy(
                value(rateLimit.enabled(), baseline.rateLimit().enabled()),
                value(rateLimit.limitForPeriod(), baseline.rateLimit().limitForPeriod()),
                value(rateLimit.refreshPeriod(), baseline.rateLimit().refreshPeriod()),
                value(rateLimit.timeoutDuration(), baseline.rateLimit().timeoutDuration())),
        value(overrides.fallbackMode(), baseline.fallbackMode()));
  }

  private void validateOwnedCapacity(final EnumMap<ResilienceComponent, ComponentPolicy> policies) {
    final int retrievalConcurrency =
        policies.get(ResilienceComponent.VECTOR_RETRIEVAL).bulkhead().maxConcurrentCalls()
            + policies.get(ResilienceComponent.LEXICAL_RETRIEVAL).bulkhead().maxConcurrentCalls();
    if (retrievalConcurrency > retrievalPoolSize) {
      throw new IllegalArgumentException(
          "vector and lexical bulkheads must not exceed retrievalPoolSize");
    }
    if (policies.get(ResilienceComponent.CLINICAL_DATA).bulkhead().maxConcurrentCalls()
        > clinicalDataPoolSize) {
      throw new IllegalArgumentException(
          "clinical-data bulkhead must not exceed clinicalDataPoolSize");
    }
  }

  private static ResilienceComponent parseComponent(final String configuredName) {
    if (configuredName == null || configuredName.isBlank()) {
      throw new IllegalArgumentException("resilience component name must not be blank");
    }
    final String canonical =
        configuredName.trim().replace('-', '_').replace('.', '_').toUpperCase(Locale.ROOT);
    try {
      return ResilienceComponent.valueOf(canonical);
    } catch (final IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "unknown resilience component: " + configuredName, exception);
    }
  }

  private static boolean value(final Boolean override, final boolean baseline) {
    return override == null ? baseline : override;
  }

  private static int value(final Integer override, final int baseline) {
    return override == null ? baseline : override;
  }

  private static float value(final Float override, final float baseline) {
    return override == null ? baseline : override;
  }

  private static <T> T value(final T override, final T baseline) {
    return override == null ? baseline : override;
  }

  public record ComponentOverrides(
      CircuitBreakerOverrides circuitBreaker,
      RetryOverrides retry,
      Duration timeout,
      BulkheadOverrides bulkhead,
      RateLimitOverrides rateLimit,
      FallbackMode fallbackMode) {}

  public record CircuitBreakerOverrides(
      Boolean enabled,
      Float failureRateThreshold,
      Integer slidingWindowSize,
      Integer minimumNumberOfCalls,
      Integer permittedCallsInHalfOpenState,
      Duration openStateDuration) {}

  public record RetryOverrides(Boolean enabled, Integer maxAttempts, Duration waitDuration) {}

  public record BulkheadOverrides(
      Boolean enabled, Integer maxConcurrentCalls, Duration maxWaitDuration) {}

  public record RateLimitOverrides(
      Boolean enabled, Integer limitForPeriod, Duration refreshPeriod, Duration timeoutDuration) {}
}
