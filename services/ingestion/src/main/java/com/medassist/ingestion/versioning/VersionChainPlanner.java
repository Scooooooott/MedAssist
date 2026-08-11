package com.medassist.ingestion.versioning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure, deterministic lifecycle planner for one logical document's versions. */
public final class VersionChainPlanner {
  private static final Comparator<VersionChainCandidate> CONFIRMED_ORDER =
      Comparator.comparing(
              (VersionChainCandidate candidate) -> candidate.metadata().effectiveDate(),
              Comparator.reverseOrder())
          .thenComparing(candidate -> candidate.metadata().version(), Comparator.reverseOrder())
          .thenComparing(VersionChainCandidate::documentVersionId);

  private static final Comparator<VersionChainCandidate> UNKNOWN_ORDER =
      Comparator.comparing(
              (VersionChainCandidate candidate) -> candidate.metadata().version(),
              Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(VersionChainCandidate::documentVersionId);

  public VersionChainPlan plan(final List<VersionChainCandidate> candidates) {
    Objects.requireNonNull(candidates, "candidates");
    if (candidates.isEmpty()) {
      return new VersionChainPlan(List.of(), List.of());
    }

    final UUID documentId = candidates.get(0).documentId();
    final Set<UUID> versionIds = new HashSet<>();
    for (final VersionChainCandidate candidate : candidates) {
      if (!documentId.equals(candidate.documentId())) {
        throw new IllegalArgumentException("all candidates must belong to one document");
      }
      if (!versionIds.add(candidate.documentVersionId())) {
        throw new IllegalArgumentException("duplicate document version id");
      }
    }

    final List<VersionChainCandidate> confirmed =
        candidates.stream()
            .filter(candidate -> candidate.metadata().status() == VersionMetadataStatus.CONFIRMED)
            .filter(candidate -> candidate.currentStatus() != VersionChainStatus.WITHDRAWN)
            .sorted(CONFIRMED_ORDER)
            .toList();
    final List<VersionChainCandidate> unknown =
        candidates.stream()
            .filter(candidate -> candidate.metadata().status() == VersionMetadataStatus.UNKNOWN)
            .sorted(UNKNOWN_ORDER)
            .toList();
    final List<VersionChainCandidate> withdrawn =
        candidates.stream()
            .filter(candidate -> candidate.currentStatus() == VersionChainStatus.WITHDRAWN)
            .sorted(UNKNOWN_ORDER)
            .toList();

    final List<VersionChainEntry> entries = new ArrayList<>();
    for (int index = 0; index < confirmed.size(); index++) {
      final VersionChainCandidate candidate = confirmed.get(index);
      final VersionChainStatus status =
          index == 0 ? VersionChainStatus.ACTIVE : VersionChainStatus.SUPERSEDED;
      final UUID supersededBy = index == 0 ? null : confirmed.get(index - 1).documentVersionId();
      entries.add(
          new VersionChainEntry(
              candidate.documentId(),
              candidate.documentVersionId(),
              candidate.metadata(),
              status,
              supersededBy));
    }
    entries.addAll(
        withdrawn.stream()
            .map(
                candidate ->
                    new VersionChainEntry(
                        candidate.documentId(),
                        candidate.documentVersionId(),
                        candidate.metadata(),
                        VersionChainStatus.WITHDRAWN,
                        null))
            .toList());
    entries.addAll(
        unknown.stream()
            .filter(candidate -> candidate.currentStatus() != VersionChainStatus.WITHDRAWN)
            .map(
                candidate ->
                    new VersionChainEntry(
                        candidate.documentId(),
                        candidate.documentVersionId(),
                        candidate.metadata(),
                        VersionChainStatus.UNKNOWN,
                        null))
            .toList());

    final List<VersionReviewQueueCommand> reviewCommands =
        unknown.stream()
            .map(
                candidate ->
                    VersionReviewQueueCommand.from(
                        candidate.documentId(),
                        candidate.documentVersionId(),
                        candidate.metadata()))
            .toList();
    return new VersionChainPlan(entries, reviewCommands);
  }
}
