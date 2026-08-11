# M2.2 Rerank Tier Experiment

## Status

`NOT MEASURED`. The code path and contract tests exist; approved online/offline
model assets and a real evaluation run are still required.

## Question and Controls

Measure whether cross-encoder reranking improves final evidence ordering and how
the online and offline profiles trade quality for latency and memory.

Keep corpus/version, source spans, embedding model, chunking strategy, retrieval
mode, RRF settings, filters, final K, hardware, batch size, seed, and request order
constant. Change only reranker profile, candidate N, or the enabled flag in
separate runs. Candidate N must cover 20, 50, and 100; final K defaults to 8 in
code and remains a controlled input.

## Metadata and Command Template

Record model asset digests, tokenizer identity, model name/version, profile,
candidate N, final K, timeout, CPU/memory limits, commit, eval-set version, and
seed. A safe run may use:

```powershell
uv run --project tools/eval-harness eval-harness `
  --input <safe-results.jsonl> --output-json <run.json> `
  --output-markdown <run.md> --split dev --quick
```

The retrieval service run must also archive configuration and the injected timeout
case. Do not store candidate text in logs or reports.

## Metrics and Acceptance Conditions

| Metric | Required result |
|---|---|
| precision@8, nDCG@8, recall@5, recall@10, MRR | `NOT MEASURED` |
| context_precision vs M2.1 | `NOT MEASURED`; improvement not claimed |
| online P50/P95/P99 for 50 candidates | `NOT MEASURED`; target P95 <300 ms |
| offline/online quality difference | `NOT MEASURED` |
| resident memory (RSS) | `NOT MEASURED`; online target <500 MB |
| timeout behavior | Must return fused candidates with a degradation event |
| candidate integrity | Reranker may reorder known candidates only |

## Result Matrix

| Profile | N | precision@8 | nDCG@8 | recall@10 | context_precision | P50/P95/P99 ms | RSS MB | Status |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| Disabled control | 50 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Online | 20 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Online | 50 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Online | 100 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Offline | 20/50/100 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | N/A | NOT MEASURED | NOT MEASURED |

## Safety and Reproducibility Checklist

- [ ] Production run used fixed, hashed model assets; no deterministic fallback.
- [ ] Timeout and backend-failure cases were executed through retrieval.
- [ ] Raw evidence text and prompts are absent from logs, JSON reports, and Markdown reports.
- [ ] Profile, model tuple, N/K, hardware, commit, and eval-set version are present.
- [ ] The selected profile, if any, is a measured selection and is not inferred from
  public benchmark claims.
