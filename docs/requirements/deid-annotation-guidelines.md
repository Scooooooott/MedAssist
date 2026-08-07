# De-identification Annotation Guidelines

Annotation data lives under `data/eval/deid/` and is not committed.

## Entity Boundaries

- Annotate direct identifiers such as names, MRNs, SSNs, phone numbers, email addresses, URLs, IP addresses, account numbers, and device identifiers.
- Annotate facility and organization names when they can identify care context.
- Annotate exact dates. Relative expressions such as "three days later" are not direct identifiers unless tied to an exact date in the same span.
- Clinician names are identifiers and should be annotated.

## Output Format

Each local annotation record should contain:

```json
{
  "document_id": "local-only-id",
  "entities": [
    {"entity_type": "PERSON", "start": 10, "end": 20}
  ]
}
```

Do not include raw PHI text in reports or committed fixtures.
