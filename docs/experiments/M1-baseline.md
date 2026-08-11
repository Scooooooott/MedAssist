# M1 Baseline

**Status: NOT MEASURED.** The code-level baseline is under verification, but the
licensed corpus, 200-record golden set, approved model assets, and configured LLM
provider are not present in this repository. Unit and contract tests are not
substitutes for corpus-quality or latency measurements.

## Execution Profile

Run the M1 control with the explicit `m1-baseline` Spring profile:

```powershell
$env:SPRING_PROFILES_ACTIVE = "m1-baseline"
```

This profile sets `medassist.retrieval.default-retrieval-mode=VECTOR_ONLY`.
The base application configuration intentionally remains `HYBRID` for M2
experiments; a measured M1 baseline must record that the `m1-baseline` profile
was active.

## Reproducibility Tuple

| Field | Value |
|---|---|
| Evaluation set | NOT MEASURED |
| Code commit | NOT MEASURED |
| Embedding model/version | NOT MEASURED |
| Judge model/version | NOT MEASURED |
| Random seed | NOT MEASURED |

## Corpus Scale

| Metric | Value |
|---|---:|
| Documents | NOT MEASURED |
| Chunks | NOT MEASURED |
| Embeddings | NOT MEASURED |

## Ingestion

| Metric | Value |
|---|---:|
| Duration | NOT MEASURED |
| Success rate | NOT MEASURED |
| Quarantine count | NOT MEASURED |

## De-identification

The source-text-free evaluator and report contract are documented in
[De-identification Baseline v1](deid-baseline-v1.md). Precision, recall, F1, and
direct-identifier recall remain `NOT MEASURED` until the approved local span set is
run. The evaluator does not accept source text.

## Retrieval and Generation

| Metric | Value |
|---|---:|
| Faithfulness | NOT MEASURED |
| Answer relevancy | NOT MEASURED |
| Context precision | NOT MEASURED |
| Context recall | NOT MEASURED |
| Citation validity | NOT MEASURED |
| Correct abstention / false abstention | NOT MEASURED |
| Recall@5 / Recall@10 | NOT MEASURED |
| MRR | NOT MEASURED |

## Latency

| Stage | Value |
|---|---:|
| Parsing | NOT MEASURED |
| De-identification | NOT MEASURED |
| Embedding | NOT MEASURED |
| Retrieval | NOT MEASURED |
| Generation | NOT MEASURED |
| End-to-end P95 | NOT MEASURED |

## Required Run

Before this baseline can be marked measured:

1. Validate the local 200-record dataset and source character ranges.
2. Run the MinIO-to-answer path three consecutive times with fixed assets.
3. Run the dev split twice and record metric variation.
4. Run `holdout-v1` once with explicit confirmation, archive the report, and mark
   only that subset consumed.
5. Record all corpus counts, stage timings, quality metrics, and the reproducibility
   tuple above.

## Known Limits

- M1 has no ingress/egress PHI guard for user questions. Public deployment remains
  blocked until M3.9.
- Production parser/model assets and the licensed evaluation corpus are external,
  gitignored prerequisites.
- No quality, latency, memory, or cost claim is inferred from synthetic unit tests.
