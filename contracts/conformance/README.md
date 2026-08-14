# Shared Contract Conformance Fixtures

`v1/` is the semantic source of truth for the four Java/Python gRPC boundaries used by M5.7:
parser, de-identification, embedding, and reranking.

The fixture suite deliberately uses protobuf TextFormat plus a tab-separated case index. Both are
readable with standard protobuf/runtime APIs, so conformance checks do not start databases, model
runtimes, object stores, or a contract broker.

- `v1/manifest.properties` registers RPCs, error codes, and intentionally absent/default fields.
- `v1/cases.tsv` classifies every case and points to its request and expected response messages.
- `v1/<contract>/` contains the shared TextFormat messages consumed by Java and Python tests.

Changes to a proto field, semantic error code, boundary rule, or expected response must update this
suite in the same change. The Java descriptor coverage test fails when a reachable field is missing,
and both language test suites fail when the same golden message no longer parses or round-trips.
