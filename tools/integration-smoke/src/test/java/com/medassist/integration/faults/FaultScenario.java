package com.medassist.integration.faults;

import java.util.List;

record FaultScenario(
    Id id, String injectedFault, String expectedBehavior, Severity severity, Driver driver) {

  enum Id {
    DEID_UNAVAILABLE,
    DEID_TIMEOUT,
    PDP_UNAVAILABLE,
    KEYCLOAK_UNAVAILABLE,
    EMBEDDING_UNAVAILABLE,
    RERANK_UNAVAILABLE,
    PARSER_UNAVAILABLE,
    REDIS_UNAVAILABLE,
    REDPANDA_UNAVAILABLE,
    POSTGRES_SLOW_QUERY,
    LLM_RATE_LIMITED,
    ALL_LLM_PROVIDERS_UNAVAILABLE,
    DATABASE_POOL_EXHAUSTED,
    RETRIEVAL_BRANCH_TIMEOUT
  }

  enum Severity {
    HARD,
    HIGH,
    MEDIUM
  }

  enum Driver {
    INJECTABLE_FAKE,
    TOXIPROXY_AND_FAKE
  }

  static List<FaultScenario> matrix() {
    return List.of(
        scenario(
            Id.DEID_UNAVAILABLE,
            "deid-svc unavailable",
            "request rejected without plaintext pass-through",
            Severity.HARD,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.DEID_TIMEOUT,
            "deid-svc timeout",
            "request rejected without plaintext pass-through",
            Severity.HARD,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.PDP_UNAVAILABLE,
            "PDP unavailable",
            "all policy enforcement points deny",
            Severity.HARD,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.KEYCLOAK_UNAVAILABLE,
            "Keycloak unavailable",
            "valid issued token remains usable and new login fails",
            Severity.HIGH,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.EMBEDDING_UNAVAILABLE,
            "embedding unavailable",
            "retrieval fails with an explicit error",
            Severity.MEDIUM,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.RERANK_UNAVAILABLE,
            "rerank unavailable",
            "original order is returned with structured degradation",
            Severity.MEDIUM,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.PARSER_UNAVAILABLE,
            "parser unavailable",
            "one document is quarantined and committed data is unchanged",
            Severity.MEDIUM,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.REDIS_UNAVAILABLE,
            "Redis unavailable",
            "cache is bypassed and rebuilt after recovery",
            Severity.MEDIUM,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.REDPANDA_UNAVAILABLE,
            "Redpanda unavailable",
            "audit is buffered locally and flushed after recovery",
            Severity.HIGH,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.POSTGRES_SLOW_QUERY,
            "Postgres slow query",
            "query returns an explicit timeout within its deadline",
            Severity.MEDIUM,
            Driver.TOXIPROXY_AND_FAKE),
        scenario(
            Id.LLM_RATE_LIMITED,
            "LLM provider returns 429",
            "bounded retry or alternate provider succeeds",
            Severity.MEDIUM,
            Driver.INJECTABLE_FAKE),
        scenario(
            Id.ALL_LLM_PROVIDERS_UNAVAILABLE,
            "all LLM providers unavailable",
            "request fails explicitly without generated content",
            Severity.HARD,
            Driver.INJECTABLE_FAKE),
        scenario(
            Id.DATABASE_POOL_EXHAUSTED,
            "database pool exhausted",
            "bulkhead rejects immediately instead of globally queueing",
            Severity.HIGH,
            Driver.INJECTABLE_FAKE),
        scenario(
            Id.RETRIEVAL_BRANCH_TIMEOUT,
            "vector or lexical branch timeout",
            "vector fails and lexical degrades explicitly without silent omission",
            Severity.MEDIUM,
            Driver.INJECTABLE_FAKE));
  }

  private static FaultScenario scenario(
      final Id id,
      final String injectedFault,
      final String expectedBehavior,
      final Severity severity,
      final Driver driver) {
    return new FaultScenario(id, injectedFault, expectedBehavior, severity, driver);
  }
}
