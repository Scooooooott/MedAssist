# Event Contracts

The M5.2 audit transport contract is the generated protobuf schema at
`contracts/proto/medassist/contracts/v1/audit_event.proto`. The former JSON event-envelope schema
was removed so there is only one event contract source of truth.

Registry settings:

- subject: `audit-events-value`
- schema type: `PROTOBUF`
- compatibility: `BACKWARD`
- Kafka value: raw `AuditEventEnvelope.toByteArray()` bytes
- Kafka key: the event UUID string

The envelope contains only the audit domain's safe metadata projection and its integrity hash. It
does not define audit-chain fields; `previous_hash` and the final chain `hash` are assigned only by
the ordered audit consumer after validation and deduplication.

`deploy/compose/compose.events.yml` mounts the protobuf source into the Redpanda initialization
container, registers the schema, and sets subject compatibility after the broker health check. The
static configuration is CI-validated; a live Registry compatibility rejection still requires the
events profile to be started in an environment with Docker available.
