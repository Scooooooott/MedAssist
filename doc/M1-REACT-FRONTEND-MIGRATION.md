# M1 React Frontend Migration Record

Date: 2026-08-07

## Scope

This record covers the stage 4 migration from the previous retrieval-service static page to the first-class React + TypeScript frontend required by M0.11 and M1.11.

## Legacy Frontend Inventory

The repository did not contain Thymeleaf templates, Thymeleaf dependencies, or Java MVC view controllers. The only legacy UI artifact was:

- `services/retrieval/src/main/resources/static/index.html`

Legacy behavior:

- accepted a question
- accepted `docType` and `publisher` filters
- called `POST /api/answer`
- displayed answer text
- displayed refusal/abstain state
- displayed timing information
- displayed applied filters
- displayed citations with expandable evidence
- highlighted a citation `quotedSpan`

The legacy page did not implement true streaming. It used a normal fetch request to `/api/answer`.

## Migration Result

Implemented `frontend/` as a Vite + React + TypeScript SPA. The frontend now owns the user-facing UI and calls existing backend REST/SSE endpoints without adding backend response fields or bypassing service contracts.

New capabilities and guardrails:

- API client for `/api/answer/stream` with fallback handling for non-streaming responses.
- Incremental SSE parsing and markdown stabilization for partial answer rendering.
- React Markdown rendering without raw HTML support.
- Citation cards with normalized `quotedSpan` highlighting.
- Virtualized evidence list for large retrieval result sets.
- Empty, loading, ready, refusal, and network error states.
- English-only visible UI text.
- Frontend lint, format, test coverage, build, and bundle-budget scripts.
- CI and `justfile` integration for frontend checks.

## Functional Parity Checklist

- [x] Question input
- [x] `docType` filter input
- [x] `publisher` filter input
- [x] Answer display
- [x] Refusal/abstain display via `abstained`, `sufficientEvidence`, and `abstainReason`
- [x] Timing display
- [x] Applied filter summary
- [x] Citation list
- [x] Expandable citation evidence
- [x] Normalized `quotedSpan` highlighting
- [x] Network error state without raw stack traces
- [x] No backend API contract changes

## Removed Legacy Surface

- Deleted `services/retrieval/src/main/resources/static/index.html`.

No Thymeleaf templates, Thymeleaf dependency, or Thymeleaf configuration existed to remove.

## Verification

Commands run successfully:

- `corepack pnpm --dir frontend run lint`
- `corepack pnpm --dir frontend run format`
- `corepack pnpm --dir frontend run test`
- `corepack pnpm --dir frontend run build`
- `.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd test`
- `python scripts\\scan_language.py`
- `python scripts\\check_forbidden_data.py`
- `git diff --check`

Frontend test coverage from Vitest:

- Statements: 91.87%
- Branches: 78.44%
- Functions: 92.85%
- Lines: 94.32%

Browser smoke test:

- Production build served from `frontend/dist`.
- Page title, root app, question input, filters, submit button, answer panel, and English visible text verified.
- Submit path verified with backend unavailable: friendly error state shown, no raw stack trace, no console errors.
- Desktop computed layout verified: workspace width 1148px, question panel about 412px, answer panel about 720px.

## Notes

The backend `/api/answer/stream` endpoint currently emits a complete `answer` event and then closes. The React client is ready for incremental SSE deltas, but true backend token streaming remains a later backend enhancement rather than a frontend migration blocker.
