package com.medassist.auditgovernance.dashboard;

import java.time.Instant;
import java.util.List;

/** Safe default until the M4.8 aggregate repository is configured. */
public final class EmptyGovernanceMetricsService implements GovernanceMetricsService {
  @Override
  public DashboardSnapshot snapshot(
      final DashboardKind dashboard, final Instant from, final Instant to) {
    return new DashboardSnapshot(
        dashboard,
        from,
        to,
        List.of(new DashboardMetric("data_status", "No data", "EMPTY")),
        List.of());
  }
}
