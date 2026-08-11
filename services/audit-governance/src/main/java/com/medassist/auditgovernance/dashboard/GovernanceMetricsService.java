package com.medassist.auditgovernance.dashboard;

import java.time.Instant;

@FunctionalInterface
public interface GovernanceMetricsService {
  DashboardSnapshot snapshot(DashboardKind dashboard, Instant from, Instant to);
}
