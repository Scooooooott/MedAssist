# ADR-007: Frontend Stack Uses React, TypeScript, and Vite

## Status

Proposed

## Date

2026-08-07

## Context

The project needs a frontend that can demonstrate streaming answers, citation inspection, precise span highlighting, refusal states, timing breakdowns, role-aware behavior, and later governance dashboards. The original plan allowed a lightweight M1 page and a fuller frontend rewrite in M6, but that would create disposable work and hide the frontend evolution from the commit history.

The frontend must also follow the global language rule: user-facing copy, ADRs, code comments, and public documentation are English.

## Decision

Use React with TypeScript as the first-class frontend stack from M1 onward. Use Vite to build a static frontend artifact served separately from the Java services or by a static hosting layer in deployment.

This project does not need server-side rendering because the application is an authenticated or demo-oriented tool, not an SEO-driven content site. The primary UX depends on client-side streaming, interactive citation expansion, local UI state, and role-sensitive cache invalidation, all of which are well served by a Vite SPA.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Thymeleaf templates | Server-rendered templates are fast to start, but they would make streaming markdown rendering, virtualized citation lists, and component-level interaction tests harder to evolve. They would also be replaced later, creating throwaway work. |
| Temporary minimal page followed by an M6 rewrite | A rewrite hides frontend engineering progress and increases total work. A single React codebase can grow from M1 baseline UI to the M6 demo. |
| Next.js static export | Static export is viable, but the project does not need file-system routing, React Server Components, SSR, or SEO features. Vite has a smaller conceptual surface for this SPA. |
| JavaScript without TypeScript | TypeScript strict mode is needed to keep API DTOs, SSE event handling, citation spans, and UI states explicit and testable. |

## Consequences

React and TypeScript become part of the M0/M1 baseline rather than a later polish task. M0 must provide the frontend scaffold, linting, formatting, type checking, tests, build scripts, and bundle budget checks. M1.11 must implement the baseline UI on this scaffold. M6.5 deepens the same application instead of replacing it.

The tradeoff is extra M0/M1 setup time and a Node-based toolchain in CI. This is acceptable because it prevents a later rewrite and makes frontend quality visible throughout the project history.
