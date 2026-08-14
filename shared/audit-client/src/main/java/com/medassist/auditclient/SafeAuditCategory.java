package com.medassist.auditclient;

/** Stable service-independent categories supported by the audit event contract. */
public enum SafeAuditCategory {
  AUTHENTICATION,
  AUTHORIZATION,
  DATA_ACCESS,
  PHI_SAFETY,
  GOVERNANCE,
  SYSTEM
}
