# Golden Set v1

The full evaluation set lives under `data/eval/golden/` and is not committed unless data-source licensing explicitly allows it.

## Record Shape

```json
{
  "id": "golden-v1-0001",
  "question": "...",
  "expected_answer": "...",
  "expected_behavior": "answer",
  "supporting_spans": [
    {
      "document_version_id": "...",
      "char_start": 100,
      "char_end": 180
    }
  ],
  "category": "guideline_fact",
  "difficulty": "easy",
  "split": "dev",
  "eval_set_version": "golden-v1"
}
```

`supporting_spans` must point to source document character ranges. Do not use chunk IDs as ground truth.

## Split Discipline

- M1 uses `holdout-v1`.
- Holdout results are run only at milestone boundaries.
- Once a holdout subset is used, mark it consumed in the local metadata.
- If a consumed holdout is reused because the sample pool is too small, reports must state the reuse count and likely optimistic bias.
