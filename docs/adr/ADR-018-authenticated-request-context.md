# ADR-018: Authenticated Request Context Boundary

## Status

Accepted as a pre-M5 implementation prerequisite.

## Context

The service contracts contain `actor` and `role` fields so authenticated identity can cross a
service boundary. Those fields are not authentication evidence. A public HTTP request must never
be allowed to select its own role, and a missing role must not silently become `CLINICIAN`.

M4 already provides `ExecutionContext` and `ContextCarrier` as the shared propagation primitive.
The authentication adapter is intentionally deferred to M5.1, where verified Keycloak claims will
be bound to that context. This ADR fixes the consumer-side boundary before the gateway exists.

## Decision

1. HTTP adapters consume identity, role, request ID, and trace ID only from the authenticated
   `ExecutionContext`.
2. `AuthenticatedRequestContext` is the single conversion point from the context to domain roles
   and correlation IDs.
3. The online request path requires exactly one known effective role. Unknown or multiple roles
   fail closed until the multi-role merge rule is explicitly decided.
4. The Agent HTTP request no longer contains a client-controlled role. Retrieval HTTP requests
   retain their internal DTO shape for service composition, but the controller overwrites any
   body role with the authenticated context role.
5. M5.1 must bind verified JWT claims to `ContextCarrier`; the gateway is not allowed to replace
   service-side validation.
6. gRPC and asynchronous adapters must derive `RequestMetadata` and task context from the same
   authenticated context. They must not reconstruct identity from user payload fields.

## Consequences

- Direct HTTP calls without a bound context now fail closed, even before the gateway is present.
- A future gateway and service resource-server adapter can be added without changing domain
  services or trusting request-body roles.
- The current browser client still targets the legacy answer path and is intentionally not
  repointed to the internal Retrieval endpoint yet. M5.1/M5.12 must move that client to the
  authenticated gateway and generation-session contract; a temporary anonymous fallback would
  reopen the bypass this ADR closes.
- Multi-role users require a later explicit policy decision; choosing a role by precedence here
  would create an undocumented authorization rule.
- The existing protobuf fields remain for wire compatibility. Their provenance is a security
  invariant, not a claim that the fields authenticate the caller.

## Verification

- `AuthenticatedRequestContextTest` covers missing, unknown, multiple, and valid role contexts.
- Agent and Retrieval HTTP adapters use the shared context boundary.
