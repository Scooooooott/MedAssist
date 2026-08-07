# ADR-008: Django Is Limited to the Internal Ops Console

## Status

Proposed

## Date

2026-08-07

## Context

The system already has four human review workflows that need table views, filtering, and controlled state transitions: feedback review, quarantine review, document version metadata confirmation, and evaluation candidate promotion. Building all of these internal screens by hand in Spring and React would add implementation time without improving the online RAG path.

The project also has a clear language boundary: Java owns business orchestration, governance, audit, and AI orchestration. Python sidecars own stateless inference endpoints. Any Django addition must not weaken that boundary.

## Decision

Use Django only for `ops-console`, an internal-only Django-based operations and review console. The console is not on the online query path, is not exposed publicly, and does not replace any Java service or Python inference sidecar.

The console may provide list, filter, detail, and action entry points for the approved human review queues. State-changing actions must call Java APIs so audit and governance remain centralized.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Replace Java services with Django | This would overturn the project language boundary and move business orchestration out of the Spring ecosystem. |
| Replace Python inference sidecars with Django | The sidecars are stateless gRPC inference endpoints. Django's strengths in admin UI and model-driven CRUD do not help that workload. |
| Build every internal review screen manually in Spring and React | This is possible, but it spends more time on internal table workflows that Django can generate with less code. |
| Expose the console as part of the public demo | The console is an internal operational tool and may expose review metadata that is not suitable for anonymous visitors or demo accounts. |

## Consequences

Django adds a small, bounded Python web component for internal operations. The benefit is faster delivery of review workflows. The cost is one additional toolchain and a need for strict boundaries: no public exposure, no direct writes to business tables, no independent policy definitions, and no bypass of RLS.
