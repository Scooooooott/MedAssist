package com.medassist.auditgovernance.dashboard;

import java.time.Instant;
import java.util.List;

public record DashboardSnapshot(
    DashboardKind dashboard,
    Instant from,
    Instant to,
    List<DashboardMetric> metrics,
    List<String> alerts) {
  public DashboardSnapshot {
    if (dashboard == null || from == null || to == null || to.isBefore(from)) {
      throw new IllegalArgumentException("dashboard time range is invalid");
    }
    metrics = List.copyOf(metrics);
    alerts = List.copyOf(alerts);
  }
}
