# ADR-023: Shared gRPC Contract Conformance Fixtures

- Status: Accepted
- Date: 2026-08-11
- Milestone: M5.7

## Context

Buf detects schema-breaking changes but cannot prove that Java clients and Python services agree on
field meaning, boundary behavior, transport errors, floating-point values, or proto3 defaults. Pact
and Spring Cloud Contract would add brokers or adapters without providing a strong native gRPC
advantage for this repository.

## Decision

Use one versioned fixture suite in `contracts/conformance/v1/`. Requests and responses use protobuf
TextFormat, while a TSV index records RPC, scenario class, boundary kind, gRPC status, semantic error
code, and trigger condition. Java and Python consume these exact files with their native protobuf
runtimes.

The Java check derives all reachable fields from protobuf descriptors and fails unless each field is
populated by a fixture or explicitly registered as a tested default. The manifest is also the error
code registry, and every registered code must have a case. Python tests compare representative
service-boundary results with the same golden responses. Tests use fakes at object-store and model
boundaries and start no database, message broker, model runtime, or network service.

Parser and de-identification are unary RPCs, so their `batch_limit` boundary is the protocol's single
request-message limit. Embedding and reranking exercise repeated-item limits. Proto3 scalar fields do
not have presence unless declared `optional`; message-field presence and scalar zero/default wire
elision are therefore asserted separately.

## Consequences

- Contract changes must update protobuf, fixtures, and the error registry together.
- Floating-point fixtures assert exact wire round trips plus a declared comparison tolerance.
- Java and Python CI can reject semantic drift without duplicating an expected-value table.
- End-to-end availability, storage, and model behavior remain owned by M1.14 and M5.9.
