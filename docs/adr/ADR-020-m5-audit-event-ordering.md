# ADR-020: Redpanda Audit Transport and Single Ordered Partition

## Status

Accepted for M5.2.

## Context

M4's audit publisher creates one hash chain. Kafka-compatible brokers only guarantee ordering
inside one partition. A multi-partition `audit-events` topic would therefore make one global hash
chain unverifiable unless a separate chain and root were defined for every partition.

M5.2 also needs a Kafka-compatible event backbone and a schema registry on the single-host demo
deployment. Running Redpanda supplies both without a separate ZooKeeper ensemble or separate schema
registry process, reducing the number of independently operated services compared with the
equivalent Apache Kafka deployment.

## Decision

The `audit-events` topic uses one partition for this single-tenant demo. Its consumer commits the
offset only after the event has been validated, deduplicated by `event_id`, and appended to the
audit chain. Other topics may use keys appropriate to their consumers. The hash-chain publisher
abstraction remains unchanged when the transport changes.

The `audit-events-value` Schema Registry subject uses
`contracts/proto/medassist/contracts/v1/audit_event.proto`, schema type `PROTOBUF`, and subject-level
`BACKWARD` compatibility. Kafka values are generated `AuditEventEnvelope` bytes. The transport
schema contains only whitelisted metadata and its payload integrity hash; previous/final hash-chain
fields are deliberately absent and are assigned by the ordered consumer.

## Consequences

- The design preserves one total order and the existing M4 chain semantics.
- Schema registration and compatibility configuration happen only after Redpanda reports healthy.
- CI validates protobuf compatibility and the static compose registration commands; live Registry
  rejection is an environment-level verification and must not be inferred from static validation.
- Throughput is intentionally bounded; scaling the audit stream requires a new ADR rather than
  silently splitting the chain.
- Topic retention and capacity remain deployment decisions, but the ordering invariant is fixed
  before M5.2 implementation.
