# ADR-022: Streaming Session Authentication and Output Boundary

## Status

Accepted and implemented for M5.12.

## Decisions

1. Use authenticated `fetch`-based SSE rather than browser-native `EventSource`. The current
   frontend already uses `fetch` and can carry an authorization header without putting a token in
   a URL. The final BFF may still use an HttpOnly cookie if the deployment needs same-origin
   sessions.
2. Use checked, bounded text chunks for client streaming. Each emitted chunk must pass the client
   output safety boundary before it is written to Redis Stream or sent to a browser. Unapproved
   drafts, raw queries, external-provider payloads, and complete candidate text are never streamed.
3. Keep `trajectory-events` disabled by default. The checkpoint projection is authoritative; a
   future event projection needs a separate use case and retention decision.
4. Set the initial review-window target to 30 days. Checkpoint retention must be at least 30 days
   and must be configured from the same named policy as feedback review retention. Capacity and
   operational values may be tuned later without weakening this ordering.
5. Keep lexical-only retrieval and retrieval-only termination disabled by default. Enabling either
   requires the M5 quality, citation, and client-safety gate described by D35.
6. Store session metadata in a Redis Hash, active membership in expiry-scored Sorted Sets, and
   approved events in a Redis Stream. Lua scripts atomically enforce idempotent creation, user and
   global active limits, state transitions, and terminal-event append. Stream reads use native
   exclusive ranges after `Last-Event-ID`; `XTRIM` and TTL bound retention. Redis unavailability is
   a `503` condition and never selects the in-memory test adapter.
7. Bind a session to the authenticated subject, exact role set, and active policy version. Status,
   subscribe/resume, and cancellation repeat those checks. A changed `policy_version` or narrowed
   `generation_actions` execution-context obligation fails closed. The configured policy version
   remains the authoritative baseline when the identity provider does not emit those optional
   obligations.
8. Run the existing synchronous `AgentEntryService` in a context-aware virtual-thread executor.
   Connection tasks use a separate virtual-thread executor. A disconnect changes subscriber state,
   not generation state; the last disconnect starts the bounded background window. Explicit cancel
   atomically appends `cancelled` before interrupting the tracked `Future`.
9. Approve the completed transient answer before emitting anything. Reject answers containing the
   raw request text, unsafe control characters, or findings from the shared
   `SensitiveContentScanner`, then split the approved answer into bounded chunks before Redis
   append. Redis receives chunks, safe citation counts, allowlisted degradation/error codes, and a
   body-free final event; it never receives the complete answer as one value. This deliberately
   favors safety over true token-time streaming.
10. Estimate the token budget conservatively as at least one token per Unicode code point.
    Enforce duration, background, token, event, byte, per-user active, global active, and TTL limits
    before or during append. Ordinary events reserve one event slot and a bounded byte allowance so
    a limit still produces one replayable structured `error` terminal event.
11. Treat request filters as bounded, untrusted request data: validate them and include them in the
    idempotency fingerprint. Carry them through `AgentRequest` and `AgentState` into retrieval RPC
    filters, where they may only narrow the tool-owned document-type allowlist. They do not become
    authorization inputs.
12. Trace create, generation execution, event append, and replay with the shared Micrometer tracer.
    Span attributes pass through the central low-cardinality allowlist and contain no query, answer,
    event payload, owner identifier, or generation identifier.
13. Emit the generation-session SLO producer contract with dotted Micrometer names that map to the
    documented Prometheus series. Count and time each session exactly once at terminal settlement,
    expose active sessions and active buffered events as gauges, and use bounded terminal and resume
    outcome tags. The duration timer publishes a percentile histogram and an explicit 5-second SLO
    bucket used by the recording rules.

## Consequences

- M5.12 must define idempotent POST creation, Redis Stream cursors, ownership checks, and replayable
  terminal events before implementation.
- A disconnected client does not automatically cancel generation; bounded background execution and
  explicit cancellation remain part of the session state machine.
- The 30-day value is a policy baseline, not a production capacity claim.
- The stream buffer defaults to a 10-minute TTL, with five additional minutes for metadata-only
  expired-session responses. These are bounded delivery values, not audit retention.
- Recovery latency is instrumented independently, but the P95 target and Redis capacity under the
  production active-session ceiling remain **NOT MEASURED** until the production-like M5 fault and
  load environment is available.
