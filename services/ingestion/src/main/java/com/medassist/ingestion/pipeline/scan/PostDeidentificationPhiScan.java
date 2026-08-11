package com.medassist.ingestion.pipeline.scan;

import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import java.util.Objects;
import java.util.Set;

/** Source-text-free result stored with an immutable chunk. */
public record PostDeidentificationPhiScan(PhiScanStatus status, Set<String> entityTypes) {
  public PostDeidentificationPhiScan {
    Objects.requireNonNull(status, "status");
    entityTypes = Set.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
    if (status == PhiScanStatus.CLEAN && !entityTypes.isEmpty()) {
      throw new IllegalArgumentException("clean PHI scan cannot contain entity types");
    }
    if (status == PhiScanStatus.SUSPECT && entityTypes.isEmpty()) {
      throw new IllegalArgumentException("suspect PHI scan requires entity types");
    }
  }
}
