# ADR-001: Java-Orchestrated System With Python Inference Sidecars

## Status

Accepted

## Date

2026-08-06

## Context

The system needs business orchestration, governance, auditability, policy enforcement, and model inference. Java has strong support for Spring Boot services, batch processing, security, testing, and long-lived enterprise code. Python has better production libraries for document parsing, PHI detection, embeddings, and reranking.

## Decision

Use Java for service orchestration, governance, auditing, security, retrieval coordination, and external APIs. Use Python only for stateless inference sidecars: `parser-svc`, `deid-svc`, and `model-svc`.

The decision rule is whether the Java ecosystem has a qualified implementation for the capability. If not, the capability can live in a Python sidecar behind a protobuf contract.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Java-only system | Document parsing, PHI NER, and embedding/reranking model support would be weaker and slower to implement. |
| Python-only system | Governance, security, batch orchestration, and enterprise service boundaries would be less aligned with the target architecture signal. |
| One mixed monolith | It would blur trust boundaries and make PHI-sensitive sidecar failure modes harder to isolate. |

## Consequences

The system pays the cost of polyglot builds, gRPC contracts, cross-language debugging, and duplicated operational conventions. In return, each runtime is used where it is strongest and Python model code remains isolated from Java governance logic.
