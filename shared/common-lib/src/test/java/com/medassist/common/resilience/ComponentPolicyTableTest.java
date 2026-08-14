package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.common.resilience.ComponentPolicy.BulkheadPolicy;
import com.medassist.common.resilience.ComponentPolicy.CircuitBreakerPolicy;
import com.medassist.common.resilience.ComponentPolicy.RateLimitPolicy;
import com.medassist.common.resilience.ComponentPolicy.RetryPolicy;
import com.medassist.common.resilience.ComponentPolicy.TimeoutPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ComponentPolicyTableTest {
  @Test
  void strictComponentsRejectFallbackConfiguration() {
    assertThatThrownBy(
            () -> policy(ResilienceComponent.DEIDENTIFICATION, FallbackMode.CACHE_BYPASS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy(ResilienceComponent.POLICY_DECISION, FallbackMode.TOOL_ERROR))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy(ResilienceComponent.EMBEDDING, FallbackMode.VECTOR_RESULTS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> policy(ResilienceComponent.VECTOR_RETRIEVAL, FallbackMode.CACHE_BYPASS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> policy(ResilienceComponent.LLM_ALL_PROVIDERS, FallbackMode.ORIGINAL_ORDER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultsExposeOnlyApprovedRetrievalAndCacheFallbacks() {
    final ComponentPolicyTable table = ComponentPolicyTable.conservativeDefaults();

    assertThat(table.require(ResilienceComponent.LEXICAL_RETRIEVAL).fallbackMode())
        .isEqualTo(FallbackMode.VECTOR_RESULTS);
    assertThat(table.require(ResilienceComponent.RERANK).fallbackMode())
        .isEqualTo(FallbackMode.ORIGINAL_ORDER);
    assertThat(table.require(ResilienceComponent.REDIS_CACHE).fallbackMode())
        .isEqualTo(FallbackMode.CACHE_BYPASS);
    assertThat(table.require(ResilienceComponent.DEIDENTIFICATION).fallbackMode())
        .isEqualTo(FallbackMode.NONE);
  }

  @Test
  void downstreamBulkheadsDoNotExceedOwnedConnectionPools() {
    final ComponentPolicyTable table = ComponentPolicyTable.conservativeDefaults();

    assertThat(
            table.require(ResilienceComponent.VECTOR_RETRIEVAL).bulkhead().maxConcurrentCalls()
                + table
                    .require(ResilienceComponent.LEXICAL_RETRIEVAL)
                    .bulkhead()
                    .maxConcurrentCalls())
        .isEqualTo(20);
    assertThat(table.require(ResilienceComponent.CLINICAL_DATA).bulkhead().maxConcurrentCalls())
        .isEqualTo(8);
  }

  private ComponentPolicy policy(
      final ResilienceComponent component, final FallbackMode fallbackMode) {
    return new ComponentPolicy(
        component,
        new CircuitBreakerPolicy(true, 50, 2, 2, 1, Duration.ofMillis(50)),
        new RetryPolicy(false, 1, Duration.ZERO),
        new TimeoutPolicy(Duration.ofSeconds(1)),
        new BulkheadPolicy(true, 1, Duration.ZERO),
        new RateLimitPolicy(false, 1, Duration.ofSeconds(1), Duration.ZERO),
        fallbackMode);
  }
}
