# ADR-002: Spring AI 2.0 For Java AI Orchestration

## Status

Accepted

## Date

2026-08-06

## Context

The Java side needs LLM provider integration, advisor-style interception, tool orchestration, and future MCP support. The requirements identified Spring AI 2.0 as a version risk that had to be confirmed before M0 implementation.

Spring AI 2.0.0 GA was confirmed on 2026-08-06 from the Spring release announcement dated 2026-06-12. Spring Boot 4.0.7 availability was confirmed from the Spring release announcement dated 2026-06-10 and Maven Central metadata.

## Decision

Use Spring AI `2.0.0` with a BOM pinned in the parent POM. Use Spring Boot `4.0.7` for the Java service baseline.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Embabel | Its Spring Boot compatibility window is not the primary target for this repository, and adopting it would add another fast-moving abstraction before the baseline exists. |
| LangChain4j | It is useful, but it would split the project away from the Spring-native advisor, observability, and application configuration model. |
| Direct vendor SDKs only | This would make provider switching and cross-cutting guardrails more repetitive across services. |

## Consequences

Spring AI APIs still need revalidation before M3 advisor and MCP work. M0 pins the version, and future API shape decisions must be recorded before implementation.
