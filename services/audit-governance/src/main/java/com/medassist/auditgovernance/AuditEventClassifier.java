package com.medassist.auditgovernance;

import java.util.Locale;

public final class AuditEventClassifier {
  private AuditEventClassifier() {}

  public static AuditEventCategory classify(final String action, final String resourceType) {
    final String normalizedAction = action.toUpperCase(Locale.ROOT);
    final String normalizedResource = resourceType.toUpperCase(Locale.ROOT);
    if (normalizedAction.contains("AUTH")) {
      return normalizedAction.contains("Z") || normalizedAction.contains("POLICY")
          ? AuditEventCategory.AUTHORIZATION
          : AuditEventCategory.AUTHENTICATION;
    }
    if (normalizedAction.contains("EXPORT") || normalizedAction.contains("DEID")) {
      return AuditEventCategory.PHI_SAFETY;
    }
    if (normalizedResource.contains("AUDIT") || normalizedResource.contains("POLICY")) {
      return AuditEventCategory.GOVERNANCE;
    }
    if (normalizedResource.contains("DATA")
        || normalizedResource.contains("DOCUMENT")
        || normalizedResource.contains("VIEW")) {
      return AuditEventCategory.DATA_ACCESS;
    }
    return AuditEventCategory.SYSTEM;
  }
}
