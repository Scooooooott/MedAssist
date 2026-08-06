# ADR-005: Contract-First Progressive Delivery

## Status

Accepted

## Date

2026-08-06

## Context

The system has Java services, Python sidecars, and several milestones. Starting all services at once would create integration ambiguity and make it hard to isolate which layer caused a failure.

## Decision

Define cross-service protobuf contracts in M0 before implementation. Deliver services progressively by milestone: skeletons in M0, baseline RAG in M1, retrieval engineering in M2, agent capabilities in M3, governance in M4, infrastructure in M5, and deployment in M6.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Implement first and write contracts later | It would invite incompatible Java/Python assumptions and weaken CI enforcement. |
| Build every service fully in parallel | It increases unfinished surface area and delays the first demonstrable RAG baseline. |
| Start as a monolith and split later | The PHI, policy, and model-runtime boundaries are part of the project's core value, not incidental deployment details. |

## Consequences

Early work can feel slower because contracts and skeletons precede visible behavior. The payoff is clearer parallel work, earlier breaking-change detection, and lower integration risk.
