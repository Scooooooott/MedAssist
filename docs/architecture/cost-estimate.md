# Initial Cost Estimate

Date: 2026-08-06

This estimate records assumptions rather than promises. It is revisited in M2.8 and M6.3.

## Development LLM Cost

Assumptions:

- 40 PRs through M3.
- PR fast evaluation set: 30 examples.
- Average judge prompt plus response: 6,000 tokens per example.
- Cheap judge model blended price assumption: USD 0.30 per 1M tokens.
- Contextual retrieval experiment: 20,000 chunks, 800 tokens per chunk, one-time generation.

Estimate:

- PR evaluation: `40 * 30 * 6000 = 7.2M tokens`, about USD 2.16 at the placeholder price.
- Contextual retrieval: `20,000 * 800 = 16M tokens`, about USD 4.80 at the placeholder price.
- Safety margin for reruns and failed experiments: 5x.
- Initial development budget target: USD 35.

## Runtime LLM Cost

Assumptions:

- Public demo traffic: 1,000 visitor queries per month.
- Cache hit rate after prewarming: 40%.
- Uncached query average: 5,000 input tokens and 700 output tokens.
- Blended model price assumption: USD 1.00 per 1M input tokens and USD 4.00 per 1M output tokens.

Estimate:

- Uncached queries: 600 per month.
- Monthly input cost: `600 * 5000 / 1M * 1.00 = USD 3.00`.
- Monthly output cost: `600 * 700 / 1M * 4.00 = USD 1.68`.
- Initial runtime LLM budget target: USD 10 per month with a kill switch.

## Infrastructure Cost

Assumptions:

- M6 target begins at a 16 GB VPS.
- Backups use object storage with low retained volume.
- Domain cost is annualized monthly.

Estimate:

- VPS: USD 20-40 per month.
- Domain: USD 1-2 per month annualized.
- Backup storage: USD 1-5 per month.
- Initial infrastructure target: USD 45 per month.

## Review Points

- M2.3 must estimate actual contextual retrieval cost before implementation.
- M2.8 must update CI evaluation cost after fast-gate size is known.
- M6.3 must replace infrastructure placeholders with real invoices or provider quotes.
