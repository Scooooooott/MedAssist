#!/bin/sh
set -eu

: "${KEYCLOAK_ADMIN:?KEYCLOAK_ADMIN is required}"
: "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"
: "${KEYCLOAK_DEMO_CLINICIAN_PASSWORD:?KEYCLOAK_DEMO_CLINICIAN_PASSWORD is required}"
: "${KEYCLOAK_DEMO_RESEARCHER_PASSWORD:?KEYCLOAK_DEMO_RESEARCHER_PASSWORD is required}"
: "${KEYCLOAK_DEMO_ADMIN_PASSWORD:?KEYCLOAK_DEMO_ADMIN_PASSWORD is required}"

SERVER_URL="http://keycloak:8080"
/opt/keycloak/bin/kcadm.sh config credentials --server "${SERVER_URL}" --realm master --user "${KEYCLOAK_ADMIN}" --password "${KEYCLOAK_ADMIN_PASSWORD}"

set_password() {
  /opt/keycloak/bin/kcadm.sh set-password --server "${SERVER_URL}" --realm medassist --username "$1" --new-password "$2"
}

set_client_secret() {
  id="$(/opt/keycloak/bin/kcadm.sh get clients -r medassist -q "clientId=$1" --fields id --format csv --noquotes)"
  test -n "${id}"
  /opt/keycloak/bin/kcadm.sh update "clients/${id}" -r medassist -s "secret=$2"
}

set_password demo-clinician "${KEYCLOAK_DEMO_CLINICIAN_PASSWORD}"
set_password demo-researcher "${KEYCLOAK_DEMO_RESEARCHER_PASSWORD}"
set_password demo-admin "${KEYCLOAK_DEMO_ADMIN_PASSWORD}"
set_client_secret medassist-gateway "${KEYCLOAK_SERVICE_GATEWAY_SECRET:?KEYCLOAK_SERVICE_GATEWAY_SECRET is required}"
set_client_secret medassist-identity-policy "${KEYCLOAK_SERVICE_IDENTITY_POLICY_SECRET:?KEYCLOAK_SERVICE_IDENTITY_POLICY_SECRET is required}"
set_client_secret medassist-audit-governance "${KEYCLOAK_SERVICE_AUDIT_GOVERNANCE_SECRET:?KEYCLOAK_SERVICE_AUDIT_GOVERNANCE_SECRET is required}"
set_client_secret medassist-clinical-data "${KEYCLOAK_SERVICE_CLINICAL_DATA_SECRET:?KEYCLOAK_SERVICE_CLINICAL_DATA_SECRET is required}"
set_client_secret medassist-ingestion "${KEYCLOAK_SERVICE_INGESTION_SECRET:?KEYCLOAK_SERVICE_INGESTION_SECRET is required}"
set_client_secret medassist-retrieval "${KEYCLOAK_SERVICE_RETRIEVAL_SECRET:?KEYCLOAK_SERVICE_RETRIEVAL_SECRET is required}"
set_client_secret medassist-agent "${KEYCLOAK_SERVICE_AGENT_SECRET:?KEYCLOAK_SERVICE_AGENT_SECRET is required}"
set_client_secret medassist-ops-console "${KEYCLOAK_SERVICE_OPS_CONSOLE_SECRET:?KEYCLOAK_SERVICE_OPS_CONSOLE_SECRET is required}"
echo "MedAssist Keycloak realm credentials initialized from the environment."
