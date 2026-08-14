# ADR-027: LLM Provider Routing and Budget Enforcement

## Status

Accepted for the current single-replica `agent` deployment. Distributed budget persistence and
live provider calibration remain deployment work.

## Decision

The `agent` service owns a provider-neutral LLM gateway. A configured ordered route selects a
concrete adapter before each call. Every provider attempt, including failover, runs the M3.9 egress
guard with that provider's final destination. An egress denial or hard-budget denial is fail-closed
and cannot fall through to another destination.

OpenAI-compatible providers share one transport adapter; provider-specific endpoints, immutable
model IDs, prices, request limits, and timeouts are configuration. The initial route contains two
external providers and a disabled-by-availability local route slot for M7.3. Moving aliases such as
`latest` are rejected during configuration binding.

The gateway reserves a conservative maximum estimated cost before network I/O, then reconciles it
with returned token usage. Global, role, and user daily/monthly windows are checked independently.
The current lock-protected ledger is correct for one `agent` replica. Before horizontal scaling it
must be replaced behind `LlmBudgetLedger` with an atomic Redis implementation; replica-local ledgers
must never be presented as a global hard limit.

Provider 429 responses honor `Retry-After` up to a configured cap, then retry the same provider once
before failover. A client-side per-provider limiter rejects excess calls before transport. Metrics
record provider, pinned model, token direction, estimated cost, retries, failures, and soft-budget
crossings without prompt or response content.

M5.12 adopts final-only or bounded approved output rather than exposing unapproved provider tokens.
For the current non-streaming provider adapter, the overall response timeout is enforced by the HTTP
client; `first-token-timeout` is retained as a required provider contract field and becomes an
independent transport deadline when a streaming adapter is introduced. It is not claimed as a
measured streaming deadline in M5.

## Consequences

- Changing provider order or destination is configuration-only and still re-evaluates egress.
- Hard limits are enforced before calls and soft limits are observable.
- Model and usage metadata are available to checkpoints, generation metadata, metrics, and the cost
  read model without storing prompts.
- Multi-replica `agent` deployment is blocked on an atomic shared budget ledger.
- Live prices, provider model availability, rate limits, and timeout calibration remain operations
  configuration and must be verified before enabling external calls.
