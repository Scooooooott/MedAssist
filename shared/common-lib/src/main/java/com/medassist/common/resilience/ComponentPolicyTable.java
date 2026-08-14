package com.medassist.common.resilience;

import com.medassist.common.resilience.ComponentPolicy.BulkheadPolicy;
import com.medassist.common.resilience.ComponentPolicy.CircuitBreakerPolicy;
import com.medassist.common.resilience.ComponentPolicy.RateLimitPolicy;
import com.medassist.common.resilience.ComponentPolicy.RetryPolicy;
import com.medassist.common.resilience.ComponentPolicy.TimeoutPolicy;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable component policy table. Runtime configuration can replace any baseline entry. */
public final class ComponentPolicyTable {
  private final Map<ResilienceComponent, ComponentPolicy> policies;

  private ComponentPolicyTable(final Collection<ComponentPolicy> policies) {
    final EnumMap<ResilienceComponent, ComponentPolicy> byComponent =
        new EnumMap<>(ResilienceComponent.class);
    for (final ComponentPolicy policy : policies) {
      Objects.requireNonNull(policy, "policy");
      if (byComponent.put(policy.component(), policy) != null) {
        throw new IllegalArgumentException("duplicate policy for " + policy.component());
      }
    }
    this.policies = Map.copyOf(byComponent);
  }

  public static ComponentPolicyTable of(final Collection<ComponentPolicy> policies) {
    return new ComponentPolicyTable(policies);
  }

  /**
   * Conservative development defaults. Values are deliberately replaceable; production tuning is
   * expected to bind environment-specific values into this table.
   */
  public static ComponentPolicyTable conservativeDefaults() {
    return of(
        List.of(
            baseline(
                ResilienceComponent.DEIDENTIFICATION,
                Duration.ofSeconds(2),
                FallbackMode.NONE,
                true),
            baseline(
                ResilienceComponent.POLICY_DECISION,
                Duration.ofMillis(500),
                FallbackMode.NONE,
                true),
            baseline(
                ResilienceComponent.EMBEDDING, Duration.ofMillis(500), FallbackMode.NONE, true),
            baseline(
                ResilienceComponent.VECTOR_RETRIEVAL,
                Duration.ofMillis(400),
                FallbackMode.NONE,
                true,
                10),
            baseline(
                ResilienceComponent.LEXICAL_RETRIEVAL,
                Duration.ofMillis(400),
                FallbackMode.VECTOR_RESULTS,
                true,
                10),
            baseline(
                ResilienceComponent.RERANK,
                Duration.ofMillis(500),
                FallbackMode.ORIGINAL_ORDER,
                true),
            baseline(
                ResilienceComponent.PARSER,
                Duration.ofSeconds(30),
                FallbackMode.DOCUMENT_QUARANTINE,
                false),
            baseline(
                ResilienceComponent.CLINICAL_DATA,
                Duration.ofSeconds(2),
                FallbackMode.TOOL_ERROR,
                false,
                8),
            baseline(
                ResilienceComponent.REDIS_CACHE,
                Duration.ofMillis(100),
                FallbackMode.CACHE_BYPASS,
                false),
            baseline(
                ResilienceComponent.REDPANDA,
                Duration.ofSeconds(1),
                FallbackMode.LOCAL_BUFFER,
                false),
            llmProvider(),
            baseline(
                ResilienceComponent.LLM_ALL_PROVIDERS,
                Duration.ofSeconds(4),
                FallbackMode.NONE,
                false)));
  }

  public ComponentPolicy require(final ResilienceComponent component) {
    final ComponentPolicy policy = policies.get(Objects.requireNonNull(component, "component"));
    if (policy == null) {
      throw new IllegalArgumentException("no resilience policy for " + component);
    }
    return policy;
  }

  public Collection<ComponentPolicy> policies() {
    return policies.values();
  }

  private static ComponentPolicy baseline(
      final ResilienceComponent component,
      final Duration timeout,
      final FallbackMode fallback,
      final boolean retryEnabled) {
    return baseline(component, timeout, fallback, retryEnabled, 16);
  }

  private static ComponentPolicy baseline(
      final ResilienceComponent component,
      final Duration timeout,
      final FallbackMode fallback,
      final boolean retryEnabled,
      final int maxConcurrentCalls) {
    return new ComponentPolicy(
        component,
        new CircuitBreakerPolicy(true, 50.0F, 20, 5, 2, Duration.ofSeconds(30)),
        new RetryPolicy(retryEnabled, retryEnabled ? 2 : 1, Duration.ofMillis(25)),
        new TimeoutPolicy(timeout),
        new BulkheadPolicy(true, maxConcurrentCalls, Duration.ZERO),
        new RateLimitPolicy(false, 1, Duration.ofSeconds(1), Duration.ZERO),
        fallback);
  }

  private static ComponentPolicy llmProvider() {
    final ComponentPolicy baseline =
        baseline(
            ResilienceComponent.LLM_PROVIDER,
            Duration.ofSeconds(4),
            FallbackMode.ALTERNATE_PROVIDER,
            false);
    return new ComponentPolicy(
        baseline.component(),
        baseline.circuitBreaker(),
        baseline.retry(),
        baseline.timeout(),
        baseline.bulkhead(),
        new RateLimitPolicy(true, 20, Duration.ofSeconds(1), Duration.ZERO),
        baseline.fallbackMode());
  }
}
