package com.medassist.ingestion.quality;

import java.util.List;
import java.util.Objects;

/** Explicit safe rejection decision for a batch with failed blocking assertions. */
public record BatchRejection(String batchId, String reason, List<String> blockingAssertionCodes) {
  public BatchRejection {
    if (batchId == null || batchId.isBlank()) {
      throw new IllegalArgumentException("batchId must not be blank");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    blockingAssertionCodes =
        List.copyOf(Objects.requireNonNull(blockingAssertionCodes, "blockingAssertionCodes"));
    if (blockingAssertionCodes.isEmpty()) {
      throw new IllegalArgumentException("blockingAssertionCodes must not be empty");
    }
  }
}
