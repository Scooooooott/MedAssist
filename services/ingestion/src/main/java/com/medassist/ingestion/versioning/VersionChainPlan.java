package com.medassist.ingestion.versioning;

import java.util.List;
import java.util.Objects;

/** Immutable planner output, including manual-review commands for unresolved candidates. */
public record VersionChainPlan(
    List<VersionChainEntry> entries, List<VersionReviewQueueCommand> reviewQueueCommands) {

  public VersionChainPlan {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(reviewQueueCommands, "reviewQueueCommands");
    entries = List.copyOf(entries);
    reviewQueueCommands = List.copyOf(reviewQueueCommands);
  }
}
