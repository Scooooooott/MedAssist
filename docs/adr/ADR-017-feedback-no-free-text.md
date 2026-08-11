# ADR-017: Structured Feedback Without User Free Text

## Status

Accepted for M4.

## Decision

User feedback is limited to overall rating, citation rating, issue category and severity. It does not accept a free-text field, because feedback is an independent PHI ingress path. An administrator may enter a candidate answer during explicit review, but it must pass a de-identification guard before it is retained as a candidate projection.

Feedback is never automatically added to an evaluation set, used to change retrieval weights, or used to alter document state. Candidate creation is an explicit, audited reviewer action and remains a handoff object for a later evaluation workflow.

## Consequences

- The public feedback surface is safe to validate without a live de-identification provider.
- Reviewers cannot use feedback submission as an unbounded clinical note field.
- A future richer review editor must add a de-identification adapter and tests before expanding the contract.
