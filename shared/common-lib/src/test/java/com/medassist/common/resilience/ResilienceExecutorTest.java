package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.common.resilience.ComponentPolicy.BulkheadPolicy;
import com.medassist.common.resilience.ComponentPolicy.CircuitBreakerPolicy;
import com.medassist.common.resilience.ComponentPolicy.RateLimitPolicy;
import com.medassist.common.resilience.ComponentPolicy.RetryPolicy;
import com.medassist.common.resilience.ComponentPolicy.TimeoutPolicy;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilienceExecutorTest {
  @Test
  void circuitOpensThenRecoversThroughHalfOpen() throws Exception {
    final ComponentPolicy policy =
        policy(false, 1, true, 2, 2, Duration.ofMillis(50), 4, false, Duration.ofSeconds(1));
    try (ResilienceExecutor executor = executor(policy, ignored -> false)) {
      assertThatThrownBy(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      true,
                      () -> {
                        throw new IllegalStateException("downstream failed");
                      }))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      true,
                      () -> {
                        throw new IllegalStateException("downstream failed");
                      }))
          .isInstanceOf(RuntimeException.class);

      assertThat(executor.circuitBreaker(ResilienceComponent.VECTOR_RETRIEVAL).getState())
          .isEqualTo(CircuitBreaker.State.OPEN);
      assertThatThrownBy(
              () ->
                  executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "not called"))
          .isInstanceOf(CallNotPermittedException.class);

      Thread.sleep(75);
      assertThat(executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "recovered"))
          .isEqualTo("recovered");
      assertThat(executor.circuitBreaker(ResilienceComponent.VECTOR_RETRIEVAL).getState())
          .isEqualTo(CircuitBreaker.State.CLOSED);
    }
  }

  @Test
  void bulkheadRejectsConcurrentCallWithoutQueuing() throws Exception {
    final ComponentPolicy policy =
        policy(false, 1, false, 10, 10, Duration.ofSeconds(1), 1, false, Duration.ofSeconds(2));
    try (ResilienceExecutor executor = executor(policy, ignored -> false)) {
      final CountDownLatch entered = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      final CompletableFuture<String> first =
          CompletableFuture.supplyAsync(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      true,
                      () -> {
                        entered.countDown();
                        release.await();
                        return "first";
                      }));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () -> executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "second"))
          .isInstanceOf(BulkheadFullException.class);

      release.countDown();
      assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
    }
  }

  @Test
  void retriesOnlyRetryableIdempotentCalls() {
    final ComponentPolicy policy =
        policy(true, 3, false, 10, 10, Duration.ofSeconds(1), 4, false, Duration.ofSeconds(1));
    try (ResilienceExecutor executor = executor(policy, ignored -> true)) {
      final AtomicInteger idempotentAttempts = new AtomicInteger();
      assertThat(
              executor.execute(
                  ResilienceComponent.VECTOR_RETRIEVAL,
                  true,
                  () -> {
                    if (idempotentAttempts.incrementAndGet() < 3) {
                      throw new IOException("temporary");
                    }
                    return "ok";
                  }))
          .isEqualTo("ok");
      assertThat(idempotentAttempts).hasValue(3);

      final AtomicInteger nonIdempotentAttempts = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      false,
                      () -> {
                        nonIdempotentAttempts.incrementAndGet();
                        throw new IOException("temporary");
                      }))
          .isInstanceOf(RuntimeException.class);
      assertThat(nonIdempotentAttempts).hasValue(1);
    }
  }

  @Test
  void deadlineCancelsSlowCall() {
    final ComponentPolicy policy =
        policy(false, 1, false, 10, 10, Duration.ofSeconds(1), 4, false, Duration.ofMillis(30));
    try (ResilienceExecutor executor = executor(policy, ignored -> false)) {
      assertThatThrownBy(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      true,
                      () -> {
                        Thread.sleep(500);
                        return "late";
                      }))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void rateLimiterRejectsCallsBeyondComponentBudget() {
    final ComponentPolicy policy =
        policy(false, 1, false, 10, 10, Duration.ofSeconds(1), 4, true, Duration.ofSeconds(1));
    try (ResilienceExecutor executor = executor(policy, ignored -> false)) {
      assertThat(executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "first"))
          .isEqualTo("first");
      assertThatThrownBy(
              () -> executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "second"))
          .isInstanceOf(RequestNotPermitted.class);
    }
  }

  @Test
  void bindsCircuitRetryAndTimeoutMetricsByComponent() {
    final ComponentPolicy policy =
        policy(true, 2, true, 10, 10, Duration.ofSeconds(1), 4, false, Duration.ofMillis(30));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (ResilienceExecutor executor = executor(policy, ignored -> true, registry)) {
      final AtomicInteger attempts = new AtomicInteger();
      assertThat(
              executor.execute(
                  ResilienceComponent.VECTOR_RETRIEVAL,
                  true,
                  () -> {
                    if (attempts.incrementAndGet() == 1) {
                      throw new IOException("temporary");
                    }
                    return "ok";
                  }))
          .isEqualTo("ok");
      assertThatThrownBy(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      false,
                      () -> {
                        Thread.sleep(200);
                        return "late";
                      }))
          .isInstanceOf(RuntimeException.class);

      assertThat(
              registry
                  .find("resilience4j.circuitbreaker.state")
                  .tag("name", "VECTOR_RETRIEVAL")
                  .meters())
          .isNotEmpty();
      assertThat(registry.find("resilience4j.retry.calls").tag("name", "VECTOR_RETRIEVAL").meters())
          .isNotEmpty();
      assertThat(
              registry
                  .find("resilience4j.timelimiter.calls")
                  .tag("name", "VECTOR_RETRIEVAL")
                  .meters())
          .isNotEmpty();
    }
  }

  @Test
  void recordsLowCardinalityBulkheadAndRateLimitRejections() throws Exception {
    final ComponentPolicy policy =
        policy(false, 1, false, 10, 10, Duration.ofSeconds(1), 1, true, Duration.ofSeconds(2));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (ResilienceExecutor executor = executor(policy, ignored -> false, registry)) {
      final CountDownLatch entered = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      final CompletableFuture<String> first =
          CompletableFuture.supplyAsync(
              () ->
                  executor.execute(
                      ResilienceComponent.VECTOR_RETRIEVAL,
                      true,
                      () -> {
                        entered.countDown();
                        release.await();
                        return "first";
                      }));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(
              () -> executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "second"))
          .isInstanceOf(BulkheadFullException.class);
      release.countDown();
      assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
      assertThatThrownBy(
              () -> executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "third"))
          .isInstanceOf(RequestNotPermitted.class);

      assertThat(
              registry
                  .get("medassist.resilience.bulkhead.rejected")
                  .tag("component", "vector_retrieval")
                  .counter()
                  .count())
          .isEqualTo(1.0D);
      assertThat(
              registry
                  .get("medassist.resilience.rate_limit.rejected")
                  .tag("component", "vector_retrieval")
                  .counter()
                  .count())
          .isEqualTo(1.0D);
    }
  }

  @Test
  void createsPayloadFreeChildSpanForEachExecution() {
    final ComponentPolicy policy =
        policy(false, 1, false, 10, 10, Duration.ofSeconds(1), 4, false, Duration.ofSeconds(1));
    final Tracer tracer = mock(Tracer.class);
    final Span span = mock(Span.class);
    final Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
    when(tracer.nextSpan()).thenReturn(span);
    when(span.name("medassist.downstream")).thenReturn(span);
    when(span.tag("component", "vector_retrieval")).thenReturn(span);
    when(span.start()).thenReturn(span);
    when(tracer.withSpan(span)).thenReturn(spanInScope);

    try (ResilienceExecutor executor =
        new ResilienceExecutor(
            ComponentPolicyTable.of(List.of(policy)),
            Executors.newFixedThreadPool(2),
            ignored -> false,
            new SimpleMeterRegistry(),
            tracer)) {
      assertThat(executor.execute(ResilienceComponent.VECTOR_RETRIEVAL, true, () -> "ok"))
          .isEqualTo("ok");
    }

    verify(tracer).nextSpan();
    verify(span).name("medassist.downstream");
    verify(span).tag("component", "vector_retrieval");
    verify(span, times(1)).tag(anyString(), anyString());
    verify(tracer).withSpan(span);
    verify(spanInScope).close();
    verify(span).end();
  }

  private ResilienceExecutor executor(
      final ComponentPolicy policy, final RetryableFailureClassifier classifier) {
    return new ResilienceExecutor(
        ComponentPolicyTable.of(List.of(policy)), Executors.newFixedThreadPool(4), classifier);
  }

  private ResilienceExecutor executor(
      final ComponentPolicy policy,
      final RetryableFailureClassifier classifier,
      final SimpleMeterRegistry registry) {
    return new ResilienceExecutor(
        ComponentPolicyTable.of(List.of(policy)),
        Executors.newFixedThreadPool(4),
        classifier,
        registry);
  }

  private ComponentPolicy policy(
      final boolean retryEnabled,
      final int maxAttempts,
      final boolean circuitEnabled,
      final int slidingWindowSize,
      final int minimumCalls,
      final Duration openDuration,
      final int maxConcurrentCalls,
      final boolean rateLimitEnabled,
      final Duration timeout) {
    return new ComponentPolicy(
        ResilienceComponent.VECTOR_RETRIEVAL,
        new CircuitBreakerPolicy(
            circuitEnabled, 50, slidingWindowSize, minimumCalls, 1, openDuration),
        new RetryPolicy(retryEnabled, maxAttempts, Duration.ZERO),
        new TimeoutPolicy(timeout),
        new BulkheadPolicy(true, maxConcurrentCalls, Duration.ZERO),
        new RateLimitPolicy(rateLimitEnabled, 1, Duration.ofSeconds(1), Duration.ZERO),
        FallbackMode.NONE);
  }
}
