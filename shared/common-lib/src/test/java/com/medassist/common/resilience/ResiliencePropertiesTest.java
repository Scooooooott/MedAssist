package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResiliencePropertiesTest {
  @Test
  void conservativeDefaultsStartWithoutOverrides() {
    final ComponentPolicyTable table = new ResilienceProperties(20, 8, null).policyTable();

    assertThat(table.require(ResilienceComponent.VECTOR_RETRIEVAL).timeout().duration())
        .isEqualTo(Duration.ofMillis(400));
    assertThat(table.require(ResilienceComponent.LEXICAL_RETRIEVAL).fallbackMode())
        .isEqualTo(FallbackMode.VECTOR_RESULTS);
  }

  @Test
  void sparseComponentOverrideCanTuneEveryProtection() {
    final ResilienceProperties.ComponentOverrides override =
        new ResilienceProperties.ComponentOverrides(
            new ResilienceProperties.CircuitBreakerOverrides(
                false, 25.0F, 10, 3, 1, Duration.ofSeconds(2)),
            new ResilienceProperties.RetryOverrides(true, 4, Duration.ofMillis(40)),
            Duration.ofMillis(750),
            new ResilienceProperties.BulkheadOverrides(true, 9, Duration.ofMillis(5)),
            new ResilienceProperties.RateLimitOverrides(
                true, 5, Duration.ofSeconds(2), Duration.ofMillis(10)),
            FallbackMode.NONE);

    final ComponentPolicy policy =
        new ResilienceProperties(20, 8, Map.of("vector-retrieval", override))
            .policyTable()
            .require(ResilienceComponent.VECTOR_RETRIEVAL);

    assertThat(policy.circuitBreaker().enabled()).isFalse();
    assertThat(policy.circuitBreaker().failureRateThreshold()).isEqualTo(25.0F);
    assertThat(policy.retry().maxAttempts()).isEqualTo(4);
    assertThat(policy.timeout().duration()).isEqualTo(Duration.ofMillis(750));
    assertThat(policy.bulkhead().maxConcurrentCalls()).isEqualTo(9);
    assertThat(policy.rateLimit().limitForPeriod()).isEqualTo(5);
    assertThat(policy.fallbackMode()).isEqualTo(FallbackMode.NONE);
  }

  @Test
  void rejectsBulkheadsLargerThanOwnedPools() {
    assertThatThrownBy(
            () ->
                new ResilienceProperties(20, 8, Map.of("vector-retrieval", bulkheadOverride(11)))
                    .policyTable())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retrievalPoolSize");

    assertThatThrownBy(
            () ->
                new ResilienceProperties(20, 8, Map.of("clinical-data", bulkheadOverride(9)))
                    .policyTable())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("clinicalDataPoolSize");
  }

  @Test
  void rejectsLooserFallbackAndDuplicateComponentAliases() {
    final ResilienceProperties.ComponentOverrides looseFallback =
        new ResilienceProperties.ComponentOverrides(
            null, null, null, null, null, FallbackMode.CACHE_BYPASS);
    assertThatThrownBy(
            () ->
                new ResilienceProperties(20, 8, Map.of("vector-retrieval", looseFallback))
                    .policyTable())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not allow fallback");

    assertThatThrownBy(
            () ->
                new ResilienceProperties(
                        20,
                        8,
                        Map.of(
                            "vector-retrieval",
                            bulkheadOverride(10),
                            "VECTOR_RETRIEVAL",
                            bulkheadOverride(10)))
                    .policyTable())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate resilience component");
  }

  private ResilienceProperties.ComponentOverrides bulkheadOverride(final int concurrency) {
    return new ResilienceProperties.ComponentOverrides(
        null,
        null,
        null,
        new ResilienceProperties.BulkheadOverrides(true, concurrency, Duration.ZERO),
        null,
        null);
  }
}
