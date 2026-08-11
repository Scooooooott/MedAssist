package com.medassist.auditgovernance.feedback;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryControlledTrajectoryQueryService
    implements ControlledTrajectoryQueryService {
  private final Map<String, TrajectoryProjection> projections = new ConcurrentHashMap<>();

  public void put(final TrajectoryProjection projection) {
    projections.put(projection.traceId(), projection);
  }

  @Override
  public Optional<TrajectoryProjection> findForReviewer(
      final String traceId, final String reviewerRole) {
    if (!"ADMIN".equals(reviewerRole) || traceId == null || traceId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(projections.get(traceId));
  }
}
