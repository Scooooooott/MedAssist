# Evaluation Data Boundary

Licensed evaluation records belong in `data/eval/golden/` and remain untracked. The
committed files under `data/eval/metadata/` contain identifiers and split discipline only;
they must never contain questions, answers, source text, or quoted spans.

`golden-v2-splits.json` fixes the planned 300-record allocation:

- 210 development records and 90 holdout records.
- `holdout-v1` is the M1 milestone subset.
- `holdout-v2` is reserved for the M2 milestone and may be consumed once.
- `holdout-v3` is reserved for the M3 milestone and may be consumed once.

The repository currently contains no licensed 300-record evaluation corpus. A milestone
must not be reported as measured, and a holdout must not be marked consumed, until the
corresponding local records have passed `medassist-eval-validate` and the real run has been
archived with its evaluation-set version, code commit, and model version.

Run validation from `tools/eval-harness`:

```powershell
../../.tools/uv.exe run medassist-eval-validate `
  --input ../../data/eval/golden/golden-v2.jsonl `
  --metadata ../../data/eval/metadata/golden-v2-splits.json `
  --expected-total 300
```
