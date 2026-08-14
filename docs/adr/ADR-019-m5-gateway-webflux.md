# ADR-019: M5 Gateway Uses WebFlux

## Status

Accepted as a pre-M5 implementation prerequisite.

## Context

M5.1 requires Spring Cloud Gateway and asks the project to choose between a reactive and Servlet
edge. The downstream business services may remain Servlet-based; the gateway's primary work is
routing and edge policy. M5.12 also needs a long-lived streaming boundary.

## Decision

Use the WebFlux variant of Spring Cloud Gateway for the edge service. Gateway filters must remain
non-blocking: JWT verification, request ID handling, rate limiting, and route selection may not
perform blocking database or network calls in the filter chain. Blocking work belongs behind an
explicit bounded adapter and is forbidden in the normal filter path.

The gateway will bridge the verified request identity into the shared execution-context contract.
Reactive context propagation is an adapter around the existing context model, not a second
authorization model.

## Consequences

- The gateway dependency and route implementation must use WebFlux APIs.
- Blocking-call checks and a non-blocking route test are part of M5.1 acceptance.
- Downstream services do not need to migrate from Servlet MVC.
- The decision is independent of the later Java virtual-thread decision for service workloads.
