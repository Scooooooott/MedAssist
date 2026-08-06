# Contract Workflow

All cross-service APIs are defined in `contracts/proto` before implementation work starts.

## Files

- `common.proto`: shared request metadata, source ranges, and error details.
- `parser.proto`: document parsing into structured IR.
- `deid.proto`: PHI detection and anonymization without returning raw PHI values.
- `model.proto`: embedding and future reranking inference contracts.

## Change Process

1. Update the relevant `.proto` file.
2. Run `buf lint contracts`.
3. Run `buf breaking contracts --against '.git#branch=main,subdir=contracts'` before merging.
4. Run `just proto-gen`.
5. Update Java and Python call sites only after generated code compiles.

Generated code is build output and is not committed.

## Versioning

The package is `medassist.contracts.v1`. Additive fields are preferred. Removing fields, renaming fields, changing field numbers, or changing scalar types is breaking and requires a new versioned package.

## Large Payload Check

M0 requires a round-trip test for 100 vectors with 1024 dimensions to verify gRPC message size behavior. The contract uses `FloatVector` wrappers so batch vectors are explicit and can be validated independently.
