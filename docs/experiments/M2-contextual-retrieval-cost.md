# M2 Contextual Retrieval Cost Gate

Status: `NOT MEASURED`

The LLM-generated context mode is blocked until this report is replaced by an approved estimate
produced from the current indexed corpus. Rule-based context has no model-call cost and is not blocked.

## Required Evidence

1. Query the current `chunk` count after M1 ingestion has completed.
2. Run the configured contextual model on exactly 10 representative chunks.
3. Record only input/output token counts in a local JSON file. Do not include chunk text.
4. Run:

   ```text
   python scripts/experiments/contextual_cost.py --sample <token-counts.json> --chunk-count <count> --input-cost-per-million <usd> --output-cost-per-million <usd> --budget-usd 25 --output <approved-estimate.json>
   ```

5. Review the provider's prompt-caching terms and adjust the estimate conservatively. The base estimator
   intentionally assumes no prompt-caching discount.
6. Store the approved estimate as a deployment artifact and configure its SHA-256. The ingestion context
   step refuses `LLM_GENERATED` mode when the artifact is missing, over budget, or has a different hash.

## Current Blocker

No M1 corpus has been indexed in this environment, and no LLM provider or price has been selected. Chunk
count, token usage, and total cost therefore cannot be measured honestly. No cost value is inferred or
fabricated in this report.
