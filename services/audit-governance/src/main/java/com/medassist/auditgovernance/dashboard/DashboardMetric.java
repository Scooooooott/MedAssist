package com.medassist.auditgovernance.dashboard;

public record DashboardMetric(String key, String value, String status) {
  public DashboardMetric {
    if (key == null || key.isBlank() || value == null || value.isBlank()) {
      throw new IllegalArgumentException("dashboard metric key and value are required");
    }
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("dashboard metric status is required");
    }
  }
}
