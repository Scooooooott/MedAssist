package com.medassist.auditgovernance.dashboard;

public enum DashboardKind {
  GOVERNANCE,
  QUALITY,
  COST;

  public static DashboardKind parse(final String value) {
    try {
      return value == null ? null : valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (final IllegalArgumentException exception) {
      return null;
    }
  }
}
