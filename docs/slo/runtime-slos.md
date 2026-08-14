# M5 Runtime SLOs

## Scope

These are request-based runtime SLOs for the MedAssist demo service. They do not express clinical
quality, policy correctness, PHI safety, or audit integrity. Compliance signals are hard stop
conditions and have no consumable error budget.

The default rolling observation window is 28 days. A request is counted only once in the owning
service. Retries and downstream spans are diagnostic signals, not additional user requests.

| SLI | Objective | Good event | Error budget over 28 days |
| --- | --- | --- | --- |
| Availability | 99.5% | Gateway request does not end with 5xx | 0.5% of eligible requests; about 201.6 minutes only under a time-based approximation |
| End-to-end answer latency | 95% under 5 s | Generation session reaches a terminal response within 5 s | 5% of completed sessions |
| Retrieval latency | 95% under 500 ms | Owning retrieval span completes within 500 ms | 5% of retrieval operations |
| Egress guard latency | 95% under 50 ms | `egress.guard` span completes within 50 ms | 5% of guard operations |

## Measurement Contracts

- Availability uses `http_server_requests_seconds_count{job="gateway"}` and 5xx status labels.
- End-to-end latency uses `medassist_generation_session_duration_seconds_bucket` and
  `medassist_generation_sessions_total`.
- Retrieval and egress latency use Collector-derived
  `medassist_span_duration_milliseconds_*` histograms. Their span names are stable, low-cardinality
  operation names, never request paths or content.
- Prometheus recording rules publish five-minute, one-hour, six-hour, and 28-day error ratios.
- Empty or missing series mean `NO DATA`, not zero errors. The dashboard must not translate absent
  SLO telemetry into a healthy state.

The generation-session producer metrics are part of the M5.12 contract. Until M5.12 emits them,
the end-to-end SLO is `NOT MEASURED`.

## Alerting Policy

Fast burn alerts require both the five-minute and one-hour windows to exceed 14.4 times the error
budget rate. Sustained burn alerts use one-hour and six-hour windows at six times the budget rate.
These thresholds prioritize early demo detection; production thresholds require traffic-volume and
on-call review.

No SLO alert may suppress or downgrade a compliance alert. The following are immediate stop
conditions regardless of current availability or latency budget:

- non-zero PHI leakage canary;
- audit hash-chain integrity failure;
- unauthorized Agent tool access;
- absence of a mandatory compliance metric while its owning service is healthy.

## Current Evidence

| Evidence | Status |
| --- | --- |
| Rule syntax validation | Pending local `promtool`/container validation |
| Live scrape of Java metrics | NOT MEASURED |
| Live scrape of Python metrics | NOT MEASURED |
| Exemplar navigation to Tempo | NOT MEASURED |
| 28-day objective compliance | NOT MEASURED |
| Alert delivery latency | NOT MEASURED |

Never replace these entries with estimates. Record the commit, workload, environment, sample
counts, Prometheus query, and UTC interval when live evidence becomes available.
