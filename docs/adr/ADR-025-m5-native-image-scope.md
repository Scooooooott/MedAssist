# ADR-025: Incremental Native Image Scope

## Status

Accepted for implementation; runtime measurements are pending a GraalVM 25 environment.

## Decision

Native image support is limited to `gateway` and `identity-policy` in M5. Both have comparatively
small reflection surfaces and no Spring AI advisor chain. `agent` is explicitly excluded because
it combines dynamic AI proxies with the M5 virtual-thread execution model. `clinical-data` is
excluded because HAPI FHIR has a broad reflection and resource surface. `ingestion`, `retrieval`,
and `audit-governance` remain JVM services until their batch, database, and Kafka integrations are
measured independently.

The two selected services retain JVM packaging. The `native` and `nativeTest` Maven profiles are
additive, and Compose selects native images only through the `native` profile. Native verification
runs in a separate scheduled or manually dispatched workflow using GraalVM 25, the Spring Boot 4
minimum.

## Consequences

- A native build failure cannot block ordinary PR feedback while the feature is being calibrated.
- A scheduled failure is still actionable and must not be reported as a successful native build.
- No native benefit claim is made until all four measurements in the experiment report are filled
  from the same host and workload.
