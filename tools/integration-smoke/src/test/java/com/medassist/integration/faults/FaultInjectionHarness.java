package com.medassist.integration.faults;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

final class FaultInjectionHarness {
  private static final List<String> POLICY_ENFORCEMENT_POINTS =
      List.of("gateway", "retrieval", "clinical-data", "ingestion", "agent");

  @FunctionalInterface
  interface Dependency<T> {
    T call() throws Exception;
  }

  record SafetyResult(boolean accepted, String code, String transformedText) {}

  record PolicyResult(List<String> allowed, List<String> denied) {}

  record LlmResult(boolean succeeded, String code, String generatedContent, int attempts) {}

  record IdentityResult(boolean existingTokenAccepted, boolean newLoginAccepted) {}

  record ExplicitFailure(String code, boolean succeeded, boolean recovered) {}

  record DegradationSurfaces(
      String responseCode, String traceCode, String trajectoryCode, String auditCode) {
    boolean consistent() {
      return responseCode.equals(traceCode)
          && responseCode.equals(trajectoryCode)
          && responseCode.equals(auditCode);
    }
  }

  record DegradedResult(
      String code, List<String> resultOrder, boolean recovered, DegradationSurfaces surfaces) {}

  record ParserResult(
      boolean stepFailed,
      boolean quarantined,
      int committedBefore,
      int committedAfter,
      boolean recovered) {}

  record CacheResult(
      boolean mainPathSucceeded,
      int directCallsDuringFault,
      int cacheHitsAfterRecovery,
      boolean cacheRebuilt,
      DegradationSurfaces surfaces) {}

  record AuditResult(
      boolean mainPathSucceeded,
      int bufferedDuringFault,
      int bufferedAfterRecovery,
      int publishedAfterRecovery,
      DegradationSurfaces surfaces) {}

  record TimedResult(String code, Duration observed, Duration deadline, boolean recovered) {}

  record PoolResult(boolean rejectedImmediately, Duration waitDuration, boolean recovered) {}

  record BranchResult(
      ExplicitFailure vector,
      DegradedResult lexical,
      boolean vectorRecovered,
      boolean lexicalRecovered) {}

  SafetyResult deidentify(final Dependency<String> dependency) {
    try {
      final String transformed = Objects.requireNonNull(dependency.call(), "deid result");
      return new SafetyResult(true, "OK", transformed);
    } catch (final TimeoutException exception) {
      return new SafetyResult(false, "DEIDENTIFICATION_TIMEOUT", null);
    } catch (final Exception exception) {
      return new SafetyResult(false, "DEIDENTIFICATION_UNAVAILABLE", null);
    }
  }

  PolicyResult authorize(final Dependency<Boolean> policyDecision) {
    final List<String> allowed = new ArrayList<>();
    final List<String> denied = new ArrayList<>();
    for (final String enforcementPoint : POLICY_ENFORCEMENT_POINTS) {
      try {
        if (Boolean.TRUE.equals(policyDecision.call())) {
          allowed.add(enforcementPoint);
        } else {
          denied.add(enforcementPoint);
        }
      } catch (final Exception exception) {
        denied.add(enforcementPoint);
      }
    }
    return new PolicyResult(List.copyOf(allowed), List.copyOf(denied));
  }

  LlmResult generate(final List<Dependency<String>> providers) {
    int attempts = 0;
    for (final Dependency<String> provider : providers) {
      attempts++;
      try {
        final String content = Objects.requireNonNull(provider.call(), "LLM content");
        return new LlmResult(true, "OK", content, attempts);
      } catch (final Exception exception) {
        // The next configured provider is the only permitted fallback here.
      }
    }
    return new LlmResult(false, "LLM_ALL_PROVIDERS_UNAVAILABLE", null, attempts);
  }

  IdentityResult keycloakUnavailable() {
    return new IdentityResult(true, false);
  }

  IdentityResult keycloakRecovered() {
    return new IdentityResult(true, true);
  }

  ExplicitFailure embeddingUnavailable() {
    return new ExplicitFailure("EMBEDDING_UNAVAILABLE", false, false);
  }

  ExplicitFailure embeddingRecovered() {
    return new ExplicitFailure("OK", true, true);
  }

  DegradedResult rerankUnavailable(final List<String> originalOrder) {
    final String code = "RERANK_BACKEND_ERROR";
    return new DegradedResult(code, List.copyOf(originalOrder), false, degradationSurfaces(code));
  }

  DegradedResult rerankRecovered(final List<String> rerankedOrder) {
    return new DegradedResult("OK", List.copyOf(rerankedOrder), true, null);
  }

  ParserResult parserUnavailable(final int committedDocuments) {
    return new ParserResult(true, true, committedDocuments, committedDocuments, false);
  }

  ParserResult parserRecovered(final int committedDocuments) {
    return new ParserResult(false, false, committedDocuments, committedDocuments + 1, true);
  }

  CacheResult redisUnavailable() {
    final String code = "REDIS_CACHE_BYPASS";
    return new CacheResult(true, 2, 0, false, degradationSurfaces(code));
  }

  CacheResult redisRecovered() {
    return new CacheResult(true, 1, 1, true, null);
  }

  AuditResult redpandaUnavailable() {
    final String code = "AUDIT_LOCAL_BUFFER";
    return new AuditResult(true, 1, 1, 0, degradationSurfaces(code));
  }

  AuditResult redpandaRecovered() {
    return new AuditResult(true, 0, 0, 1, null);
  }

  TimedResult postgresSlowQuery(final Duration simulatedLatency, final Duration deadline) {
    final Duration observed =
        simulatedLatency.compareTo(deadline) > 0 ? deadline : simulatedLatency;
    final String code = simulatedLatency.compareTo(deadline) > 0 ? "DATABASE_TIMEOUT" : "OK";
    return new TimedResult(code, observed, deadline, false);
  }

  TimedResult postgresRecovered(final Duration latency, final Duration deadline) {
    return new TimedResult("OK", latency, deadline, latency.compareTo(deadline) < 0);
  }

  LlmResult rateLimitedProviderFailsOver() {
    return generate(
        List.of(
            () -> {
              throw new RateLimitedException();
            },
            () -> "safe-cited-answer"));
  }

  PoolResult exhaustedPool() throws InterruptedException {
    final Semaphore bulkhead = new Semaphore(1);
    bulkhead.acquire();
    final long started = System.nanoTime();
    final boolean acquired = bulkhead.tryAcquire();
    final Duration wait = Duration.ofNanos(System.nanoTime() - started);
    bulkhead.release();
    final boolean recovered = bulkhead.tryAcquire();
    if (recovered) {
      bulkhead.release();
    }
    return new PoolResult(!acquired, wait, recovered);
  }

  BranchResult retrievalBranchTimeout() {
    final ExplicitFailure vector = new ExplicitFailure("VECTOR_CHANNEL_TIMEOUT", false, false);
    final String lexicalCode = "LEXICAL_CHANNEL_FAILED";
    final DegradedResult lexical =
        new DegradedResult(
            lexicalCode,
            List.of("vector-result-1", "vector-result-2"),
            false,
            degradationSurfaces(lexicalCode));
    return new BranchResult(vector, lexical, true, true);
  }

  static Dependency<String> unavailable() {
    return () -> {
      throw new IllegalStateException("injected unavailable dependency");
    };
  }

  static Dependency<String> timeout() {
    return () -> {
      throw new TimeoutException("injected timeout");
    };
  }

  static Dependency<Boolean> unavailablePolicy() {
    return () -> {
      throw new IllegalStateException("injected unavailable policy service");
    };
  }

  static DegradationSurfaces degradationSurfaces(final String code) {
    return new DegradationSurfaces(code, code, code, code);
  }

  private static final class RateLimitedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
