package com.medassist.auditgovernance.feedback;

import java.util.Optional;

public interface ControlledTrajectoryQueryService {
  Optional<TrajectoryProjection> findForReviewer(String traceId, String reviewerRole);
}
