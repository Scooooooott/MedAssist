package com.medassist.common.resilience;

import com.medassist.common.resilience.ComponentPolicy.BulkheadPolicy;
import com.medassist.common.resilience.ComponentPolicy.CircuitBreakerPolicy;
import com.medassist.common.resilience.ComponentPolicy.RateLimitPolicy;
import com.medassist.common.resilience.ComponentPolicy.RetryPolicy;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies component-specific protection without inventing a fallback value. */
public final class ResilienceExecutor implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ResilienceExecutor.class);
  private static final String COMPONENT_TAG = "component";

  private final ComponentPolicyTable policyTable;
  private final ExecutorService timeoutExecutor;
  private final RetryableFailureClassifier retryableFailures;
  private final MeterRegistry meterRegistry;
  private final Tracer tracer;
  private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
  private final RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
  private final TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.ofDefaults();
  private final BulkheadRegistry bulkheadRegistry = BulkheadRegistry.ofDefaults();
  private final RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
  private final Map<ResilienceComponent, CircuitBreaker> circuitBreakers =
      new EnumMap<>(ResilienceComponent.class);
  private final Map<ResilienceComponent, Retry> retries = new EnumMap<>(ResilienceComponent.class);
  private final Map<ResilienceComponent, TimeLimiter> timeLimiters =
      new EnumMap<>(ResilienceComponent.class);
  private final Map<ResilienceComponent, Bulkhead> bulkheads =
      new EnumMap<>(ResilienceComponent.class);
  private final Map<ResilienceComponent, RateLimiter> rateLimiters =
      new EnumMap<>(ResilienceComponent.class);

  public ResilienceExecutor(
      final ComponentPolicyTable policyTable, final ExecutorService timeoutExecutor) {
    this(policyTable, timeoutExecutor, RetryableFailureClassifier.transportFailures());
  }

  public ResilienceExecutor(
      final ComponentPolicyTable policyTable,
      final ExecutorService timeoutExecutor,
      final RetryableFailureClassifier retryableFailures) {
    this(policyTable, timeoutExecutor, retryableFailures, null, null);
  }

  public ResilienceExecutor(
      final ComponentPolicyTable policyTable,
      final ExecutorService timeoutExecutor,
      final RetryableFailureClassifier retryableFailures,
      final MeterRegistry meterRegistry) {
    this(policyTable, timeoutExecutor, retryableFailures, meterRegistry, null);
  }

  public ResilienceExecutor(
      final ComponentPolicyTable policyTable,
      final ExecutorService timeoutExecutor,
      final RetryableFailureClassifier retryableFailures,
      final MeterRegistry meterRegistry,
      final Tracer tracer) {
    this.policyTable = Objects.requireNonNull(policyTable, "policyTable");
    this.timeoutExecutor = Objects.requireNonNull(timeoutExecutor, "timeoutExecutor");
    this.retryableFailures = Objects.requireNonNull(retryableFailures, "retryableFailures");
    this.meterRegistry = meterRegistry;
    this.tracer = tracer;
    if (meterRegistry != null) {
      bindMetrics(meterRegistry);
    }
    policyTable.policies().forEach(this::register);
  }

  public <T> T execute(
      final ResilienceComponent component, final boolean idempotent, final Callable<T> operation) {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(operation, "operation");
    if (tracer == null) {
      return executeGuarded(component, idempotent, operation);
    }
    final Span span =
        tracer
            .nextSpan()
            .name("medassist.downstream")
            .tag(COMPONENT_TAG, componentTag(component))
            .start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return executeGuarded(component, idempotent, operation);
    } catch (final RuntimeException | Error exception) {
      span.error(exception);
      throw exception;
    } finally {
      span.end();
    }
  }

  private <T> T executeGuarded(
      final ResilienceComponent component, final boolean idempotent, final Callable<T> operation) {
    final ComponentPolicy policy = policyTable.require(component);

    Callable<T> guarded =
        () ->
            timeLimiters
                .get(component)
                .executeFutureSupplier(() -> timeoutExecutor.submit(operation));
    if (policy.circuitBreaker().enabled()) {
      guarded = CircuitBreaker.decorateCallable(circuitBreakers.get(component), guarded);
    }
    if (policy.rateLimit().enabled()) {
      guarded = RateLimiter.decorateCallable(rateLimiters.get(component), guarded);
    }
    if (policy.retry().enabled() && idempotent) {
      guarded = Retry.decorateCallable(retries.get(component), guarded);
    }
    if (policy.bulkhead().enabled()) {
      guarded = Bulkhead.decorateCallable(bulkheads.get(component), guarded);
    }

    try {
      return guarded.call();
    } catch (final RuntimeException exception) {
      throw exception;
    } catch (final Exception exception) {
      if (exception.getCause() instanceof RuntimeException runtimeCause) {
        throw runtimeCause;
      }
      throw new ResilienceExecutionException(component, component + " call failed", exception);
    }
  }

  public CircuitBreaker circuitBreaker(final ResilienceComponent component) {
    return circuitBreakers.get(Objects.requireNonNull(component, "component"));
  }

  @Override
  public void close() {
    timeoutExecutor.shutdownNow();
  }

  private void register(final ComponentPolicy policy) {
    final ResilienceComponent component = policy.component();
    final String name = component.name();
    final CircuitBreaker circuitBreaker =
        circuitBreakerRegistry.circuitBreaker(name, toConfig(policy.circuitBreaker()));
    final Retry retry = retryRegistry.retry(name, toConfig(policy.retry()));
    final TimeLimiter timeLimiter =
        timeLimiterRegistry.timeLimiter(
            name,
            TimeLimiterConfig.custom()
                .timeoutDuration(policy.timeout().duration())
                .cancelRunningFuture(true)
                .build());
    final Bulkhead bulkhead = bulkheadRegistry.bulkhead(name, toConfig(policy.bulkhead()));
    final RateLimiter rateLimiter =
        rateLimiterRegistry.rateLimiter(name, toConfig(policy.rateLimit()));
    circuitBreakers.put(component, circuitBreaker);
    retries.put(component, retry);
    timeLimiters.put(component, timeLimiter);
    bulkheads.put(component, bulkhead);
    rateLimiters.put(component, rateLimiter);
    registerSafeEvents(component, circuitBreaker, bulkhead, rateLimiter);
  }

  private void bindMetrics(final MeterRegistry registry) {
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(registry);
    TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(registry);
    TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timeLimiterRegistry).bindTo(registry);
    TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(registry);
    TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiterRegistry).bindTo(registry);
  }

  private void registerSafeEvents(
      final ResilienceComponent component,
      final CircuitBreaker circuitBreaker,
      final Bulkhead bulkhead,
      final RateLimiter rateLimiter) {
    final Counter stateTransitions =
        counter("medassist.resilience.circuit_breaker.state_transitions", component);
    final Counter bulkheadRejected = counter("medassist.resilience.bulkhead.rejected", component);
    final Counter rateLimitRejected =
        counter("medassist.resilience.rate_limit.rejected", component);
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event -> {
              final CircuitBreaker.StateTransition transition = event.getStateTransition();
              LOGGER.warn(
                  "Resilience circuit state transition component={} from={} to={}",
                  component,
                  transition.getFromState(),
                  transition.getToState());
              increment(stateTransitions);
            });
    bulkhead.getEventPublisher().onCallRejected(ignored -> increment(bulkheadRejected));
    rateLimiter.getEventPublisher().onFailure(ignored -> increment(rateLimitRejected));
  }

  private Counter counter(final String metricName, final ResilienceComponent component) {
    if (meterRegistry == null) {
      return null;
    }
    return Counter.builder(metricName)
        .tag(COMPONENT_TAG, componentTag(component))
        .register(meterRegistry);
  }

  private void increment(final Counter counter) {
    if (counter != null) {
      counter.increment();
    }
  }

  private static String componentTag(final ResilienceComponent component) {
    return component.name().toLowerCase(Locale.ROOT);
  }

  private CircuitBreakerConfig toConfig(final CircuitBreakerPolicy policy) {
    return CircuitBreakerConfig.custom()
        .failureRateThreshold(policy.failureRateThreshold())
        .slidingWindowSize(policy.slidingWindowSize())
        .minimumNumberOfCalls(policy.minimumNumberOfCalls())
        .permittedNumberOfCallsInHalfOpenState(policy.permittedCallsInHalfOpenState())
        .waitDurationInOpenState(policy.openStateDuration())
        .build();
  }

  private RetryConfig toConfig(final RetryPolicy policy) {
    return RetryConfig.custom()
        .maxAttempts(policy.maxAttempts())
        .waitDuration(policy.waitDuration())
        .retryOnException(retryableFailures)
        .build();
  }

  private BulkheadConfig toConfig(final BulkheadPolicy policy) {
    return BulkheadConfig.custom()
        .maxConcurrentCalls(policy.maxConcurrentCalls())
        .maxWaitDuration(policy.maxWaitDuration())
        .build();
  }

  private RateLimiterConfig toConfig(final RateLimitPolicy policy) {
    return RateLimiterConfig.custom()
        .limitForPeriod(policy.limitForPeriod())
        .limitRefreshPeriod(policy.refreshPeriod())
        .timeoutDuration(policy.timeoutDuration())
        .build();
  }
}
