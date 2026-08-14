# MedAssist Gateway

The gateway is the authenticated WebFlux edge for the seven-service Java topology. It routes to
the six downstream Java services and owns the generation-session edge routes. Python model
services are intentionally not exposed here.

## Security boundary

- Every route except health probes and CORS preflight requires a JWT resource-server token.
- The token must resolve to exactly one of `CLINICIAN`, `RESEARCHER`, or `ADMIN`.
- Client-supplied identity and role headers are removed. The original `Authorization` header is
  preserved so each downstream service can independently verify the token.
- Generation creation, status, cancellation, and event routes use the same authenticated boundary.
- Request and response bodies are never logged. Enabling either body-logging property prevents
  startup until an approved redacting logger exists.

## Routes

| Route ID | Public path | Destination setting |
|---|---|---|
| `identity-policy` | `/api/identity/**` | `MEDASSIST_IDENTITY_POLICY_URI` |
| `ingestion` | `/ingestion/**` | `MEDASSIST_INGESTION_URI` |
| `clinical-data` | `/api/clinical/**` | `MEDASSIST_CLINICAL_DATA_URI` |
| `retrieval` | `/api/evaluations/**`, `/api/documents/**` | `MEDASSIST_RETRIEVAL_URI` |
| `agent` | `/api/agent/**` | `MEDASSIST_AGENT_URI` |
| `audit-governance` | `/api/governance/**` | `MEDASSIST_AUDIT_GOVERNANCE_URI` |
| `generation-events` | `/api/generations/*/events` | `MEDASSIST_AGENT_URI` |
| `generation-sessions` | `/api/generations/**` | `MEDASSIST_AGENT_URI` |

The event route disables the downstream response timeout for authenticated SSE. Other routes use
the configured global or generation-session timeout.

Retrieval's `/internal/**` endpoints are deliberately absent. Public answer traffic enters through
Agent or the generation-session routes, so the de-identification and output-safety path cannot be
bypassed by a gateway rewrite.

## Problem codes

All gateway-generated errors use `application/problem+json` and include `type`, `title`, `status`,
`detail`, `instance`, `code`, and `request_id`.

| Code | Status | Meaning |
|---|---:|---|
| `unauthorized` | 401 | Bearer token is absent, invalid, expired, or has no single effective role. |
| `forbidden` | 403 | The authenticated subject is not allowed to access the resource. |
| `payload-too-large` | 413 | The request exceeded the gateway body-size policy. |
| `rate-limit-exceeded` | 429 | A user, IP, or endpoint bucket is exhausted; `Retry-After` is returned. |
| `rate-limit-unavailable` | 503 | Redis could not safely evaluate the limit; the request was rejected. |
| `gateway-timeout` | 504 | A downstream operation exceeded its timeout. |
| `gateway-<status>` | varies | A downstream or routing status was normalized by the gateway. |
| `internal-error` | 500 | An unexpected gateway failure occurred. |

## Production configuration

JWK, Redis, CORS origin, downstream URI, body-size, timeout, and rate-limit values are environment
driven in `application.yml`. Defaults are for local development only. No production secret belongs
in this module or its configuration file.
