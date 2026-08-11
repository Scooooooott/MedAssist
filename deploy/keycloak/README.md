# Keycloak bootstrap

`realm-medassist.json` is imported by the governance compose profile. It contains
roles, client metadata, and demo user placeholders only. It intentionally does
not contain a password or client secret.

Provide the variables consumed by `compose.governance.yml` before starting the
profile. Generate values locally with a password manager or a cryptographically
secure generator; do not commit them to the repository.

The post-start init container uses `kcadm.sh` to set the three demo passwords
and all confidential client secrets. Re-running it is safe and keeps secret
material outside the realm export.
