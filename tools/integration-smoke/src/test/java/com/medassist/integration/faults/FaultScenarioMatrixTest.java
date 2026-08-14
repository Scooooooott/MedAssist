package com.medassist.integration.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("fault-nightly")
class FaultScenarioMatrixTest {
  private final FaultInjectionHarness harness = new FaultInjectionHarness();

  static Stream<FaultScenario> scenarios() {
    return FaultScenario.matrix().stream();
  }

  @Test
  void matrixContainsEveryRequirementRowExactlyOnce() {
    final List<FaultScenario> matrix = FaultScenario.matrix();
    final EnumSet<FaultScenario.Id> ids = EnumSet.noneOf(FaultScenario.Id.class);

    matrix.forEach(
        scenario -> {
          assertTrue(ids.add(scenario.id()), () -> "duplicate scenario " + scenario.id());
          assertFalse(scenario.injectedFault().isBlank());
          assertFalse(scenario.expectedBehavior().isBlank());
          assertNotNull(scenario.severity());
          assertNotNull(scenario.driver());
        });

    assertEquals(EnumSet.allOf(FaultScenario.Id.class), ids);
    assertEquals(14, matrix.size());
    assertEquals(
        4,
        matrix.stream()
            .filter(scenario -> scenario.severity() == FaultScenario.Severity.HARD)
            .count());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void injectedFaultMatchesExpectedBehaviorAndRecovers(final FaultScenario scenario)
      throws InterruptedException {
    switch (scenario.id()) {
      case DEID_UNAVAILABLE -> verifyDeidentificationUnavailable();
      case DEID_TIMEOUT -> verifyDeidentificationTimeout();
      case PDP_UNAVAILABLE -> verifyPolicyUnavailable();
      case KEYCLOAK_UNAVAILABLE -> verifyKeycloakUnavailable();
      case EMBEDDING_UNAVAILABLE -> verifyEmbeddingUnavailable();
      case RERANK_UNAVAILABLE -> verifyRerankUnavailable();
      case PARSER_UNAVAILABLE -> verifyParserUnavailable();
      case REDIS_UNAVAILABLE -> verifyRedisUnavailable();
      case REDPANDA_UNAVAILABLE -> verifyRedpandaUnavailable();
      case POSTGRES_SLOW_QUERY -> verifyPostgresSlowQuery();
      case LLM_RATE_LIMITED -> verifyLlmRateLimited();
      case ALL_LLM_PROVIDERS_UNAVAILABLE -> verifyAllLlmUnavailable();
      case DATABASE_POOL_EXHAUSTED -> verifyDatabasePoolExhausted();
      case RETRIEVAL_BRANCH_TIMEOUT -> verifyRetrievalBranchTimeout();
    }
  }

  private void verifyDeidentificationUnavailable() {
    final FaultInjectionHarness.SafetyResult failed =
        harness.deidentify(FaultInjectionHarness.unavailable());
    assertFalse(failed.accepted());
    assertNull(failed.transformedText());
    assertTrue(harness.deidentify(() -> "safe").accepted());
  }

  private void verifyDeidentificationTimeout() {
    final FaultInjectionHarness.SafetyResult failed =
        harness.deidentify(FaultInjectionHarness.timeout());
    assertFalse(failed.accepted());
    assertEquals("DEIDENTIFICATION_TIMEOUT", failed.code());
    assertTrue(harness.deidentify(() -> "safe").accepted());
  }

  private void verifyPolicyUnavailable() {
    final FaultInjectionHarness.PolicyResult failed =
        harness.authorize(FaultInjectionHarness.unavailablePolicy());
    assertTrue(failed.allowed().isEmpty());
    assertEquals(5, failed.denied().size());
    assertTrue(harness.authorize(() -> true).denied().isEmpty());
  }

  private void verifyKeycloakUnavailable() {
    final FaultInjectionHarness.IdentityResult failed = harness.keycloakUnavailable();
    assertTrue(failed.existingTokenAccepted());
    assertFalse(failed.newLoginAccepted());
    assertTrue(harness.keycloakRecovered().newLoginAccepted());
  }

  private void verifyEmbeddingUnavailable() {
    final FaultInjectionHarness.ExplicitFailure failed = harness.embeddingUnavailable();
    assertFalse(failed.succeeded());
    assertEquals("EMBEDDING_UNAVAILABLE", failed.code());
    assertTrue(harness.embeddingRecovered().recovered());
  }

  private void verifyRerankUnavailable() {
    final List<String> original = List.of("chunk-a", "chunk-b");
    final FaultInjectionHarness.DegradedResult failed = harness.rerankUnavailable(original);
    assertEquals(original, failed.resultOrder());
    assertEquals("RERANK_BACKEND_ERROR", failed.code());
    assertTrue(failed.surfaces().consistent());
    assertTrue(harness.rerankRecovered(List.of("chunk-b", "chunk-a")).recovered());
  }

  private void verifyParserUnavailable() {
    final FaultInjectionHarness.ParserResult failed = harness.parserUnavailable(7);
    assertTrue(failed.stepFailed());
    assertTrue(failed.quarantined());
    assertEquals(failed.committedBefore(), failed.committedAfter());
    assertTrue(harness.parserRecovered(7).recovered());
  }

  private void verifyRedisUnavailable() {
    final FaultInjectionHarness.CacheResult failed = harness.redisUnavailable();
    assertTrue(failed.mainPathSucceeded());
    assertEquals(2, failed.directCallsDuringFault());
    assertTrue(failed.surfaces().consistent());
    final FaultInjectionHarness.CacheResult recovered = harness.redisRecovered();
    assertTrue(recovered.cacheRebuilt());
    assertEquals(1, recovered.cacheHitsAfterRecovery());
  }

  private void verifyRedpandaUnavailable() {
    final FaultInjectionHarness.AuditResult failed = harness.redpandaUnavailable();
    assertTrue(failed.mainPathSucceeded());
    assertEquals(1, failed.bufferedDuringFault());
    assertTrue(failed.surfaces().consistent());
    final FaultInjectionHarness.AuditResult recovered = harness.redpandaRecovered();
    assertEquals(0, recovered.bufferedAfterRecovery());
    assertEquals(1, recovered.publishedAfterRecovery());
  }

  private void verifyPostgresSlowQuery() {
    final Duration deadline = Duration.ofMillis(250);
    final FaultInjectionHarness.TimedResult failed =
        harness.postgresSlowQuery(Duration.ofSeconds(2), deadline);
    assertEquals("DATABASE_TIMEOUT", failed.code());
    assertTrue(failed.observed().compareTo(deadline) <= 0);
    assertTrue(harness.postgresRecovered(Duration.ofMillis(20), deadline).recovered());
  }

  private void verifyLlmRateLimited() {
    final FaultInjectionHarness.LlmResult result = harness.rateLimitedProviderFailsOver();
    assertTrue(result.succeeded());
    assertEquals(2, result.attempts());
    assertEquals("safe-cited-answer", result.generatedContent());
  }

  private void verifyAllLlmUnavailable() {
    final FaultInjectionHarness.LlmResult failed =
        harness.generate(
            List.of(FaultInjectionHarness.unavailable(), FaultInjectionHarness.unavailable()));
    assertFalse(failed.succeeded());
    assertEquals("LLM_ALL_PROVIDERS_UNAVAILABLE", failed.code());
    assertNull(failed.generatedContent());
    assertTrue(harness.generate(List.of(() -> "safe-cited-answer")).succeeded());
  }

  private void verifyDatabasePoolExhausted() throws InterruptedException {
    final FaultInjectionHarness.PoolResult failed = harness.exhaustedPool();
    assertTrue(failed.rejectedImmediately());
    assertTrue(failed.waitDuration().compareTo(Duration.ofMillis(250)) < 0);
    assertTrue(failed.recovered());
  }

  private void verifyRetrievalBranchTimeout() {
    final FaultInjectionHarness.BranchResult result = harness.retrievalBranchTimeout();
    assertFalse(result.vector().succeeded());
    assertEquals("VECTOR_CHANNEL_TIMEOUT", result.vector().code());
    assertEquals("LEXICAL_CHANNEL_FAILED", result.lexical().code());
    assertTrue(result.lexical().surfaces().consistent());
    assertTrue(result.vectorRecovered());
    assertTrue(result.lexicalRecovered());
  }
}
