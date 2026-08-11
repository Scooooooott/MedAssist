package com.medassist.ingestion.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medassist.domain.DocumentIR;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VersionChainPlannerTest {
  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final VersionChainPlanner planner = new VersionChainPlanner();

  @Test
  void sortsConfirmedVersionsAndBuildsSupersessionLinks() {
    final VersionChainCandidate old =
        candidate("00000000-0000-0000-0000-000000000010", "v1", "2024-01-01");
    final VersionChainCandidate latest =
        candidate("00000000-0000-0000-0000-000000000011", "v2", "2024-06-01");

    final VersionChainPlan plan = planner.plan(List.of(old, latest));

    assertEquals(2, plan.entries().size());
    assertEquals(latest.documentVersionId(), plan.entries().get(0).documentVersionId());
    assertEquals(VersionChainStatus.ACTIVE, plan.entries().get(0).status());
    assertEquals(old.documentVersionId(), plan.entries().get(1).documentVersionId());
    assertEquals(VersionChainStatus.SUPERSEDED, plan.entries().get(1).status());
    assertEquals(latest.documentVersionId(), plan.entries().get(1).supersededBy());
  }

  @Test
  void withdrawnVersionRemainsWithdrawnAndCannotWinActiveSlot() {
    final VersionChainCandidate withdrawn =
        new VersionChainCandidate(
            DOCUMENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000020"),
            metadata("v3", "2025-01-01"),
            VersionChainStatus.WITHDRAWN);
    final VersionChainCandidate activeCandidate =
        candidate("00000000-0000-0000-0000-000000000021", "v2", "2024-01-01");

    final VersionChainPlan plan = planner.plan(List.of(activeCandidate, withdrawn));

    assertEquals(VersionChainStatus.ACTIVE, entry(plan, activeCandidate).status());
    assertEquals(VersionChainStatus.WITHDRAWN, entry(plan, withdrawn).status());
    assertNull(entry(plan, withdrawn).supersededBy());
  }

  @Test
  void unknownWithdrawnVersionStaysWithdrawnButStillRequiresReview() {
    final VersionChainCandidate withdrawnUnknown =
        new VersionChainCandidate(
            DOCUMENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000022"),
            new DocumentVersionMetadataExtractor()
                .extract(new DocumentIR(List.of(), List.of(), Map.of())),
            VersionChainStatus.WITHDRAWN);

    final VersionChainPlan plan = planner.plan(List.of(withdrawnUnknown));

    assertEquals(VersionChainStatus.WITHDRAWN, entry(plan, withdrawnUnknown).status());
    assertEquals(1, plan.reviewQueueCommands().size());
    assertEquals(
        withdrawnUnknown.documentVersionId(),
        plan.reviewQueueCommands().get(0).documentVersionId());
  }

  @Test
  void unknownVersionIsNeverActivatedAndGetsReviewCommand() {
    final VersionMetadataResult unknown =
        new DocumentVersionMetadataExtractor()
            .extract(new DocumentIR(List.of(), List.of(), Map.of()));
    final VersionChainCandidate unknownCandidate =
        new VersionChainCandidate(
            DOCUMENT_ID,
            UUID.fromString("00000000-0000-0000-0000-000000000030"),
            unknown,
            VersionChainStatus.ACTIVE);
    final VersionChainCandidate confirmed =
        candidate("00000000-0000-0000-0000-000000000031", "v1", "2024-01-01");

    final VersionChainPlan plan = planner.plan(List.of(unknownCandidate, confirmed));

    assertEquals(VersionChainStatus.ACTIVE, entry(plan, confirmed).status());
    assertEquals(VersionChainStatus.UNKNOWN, entry(plan, unknownCandidate).status());
    assertEquals(1, plan.reviewQueueCommands().size());
    assertEquals(
        unknownCandidate.documentVersionId(),
        plan.reviewQueueCommands().get(0).documentVersionId());
  }

  @Test
  void sameDateUsesStableVersionAndIdTieBreakersRegardlessOfInputOrder() {
    final VersionChainCandidate lowerVersion =
        candidate("00000000-0000-0000-0000-000000000040", "v1", "2024-01-01");
    final VersionChainCandidate higherVersion =
        candidate("00000000-0000-0000-0000-000000000041", "v2", "2024-01-01");

    final VersionChainPlan first = planner.plan(List.of(lowerVersion, higherVersion));
    final VersionChainPlan second = planner.plan(List.of(higherVersion, lowerVersion));

    assertEquals(first, second);
    assertEquals(higherVersion.documentVersionId(), first.entries().get(0).documentVersionId());
    assertEquals(VersionChainStatus.ACTIVE, first.entries().get(0).status());
  }

  @Test
  void rejectsMixedDocumentsAndDuplicateVersions() {
    final VersionChainCandidate first =
        candidate("00000000-0000-0000-0000-000000000050", "v1", "2024-01-01");
    final VersionChainCandidate duplicate =
        new VersionChainCandidate(
            DOCUMENT_ID,
            first.documentVersionId(),
            first.metadata(),
            VersionChainStatus.SUPERSEDED);
    final VersionChainCandidate otherDocument =
        new VersionChainCandidate(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000051"),
            first.metadata(),
            VersionChainStatus.ACTIVE);

    assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(first, duplicate)));
    assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(first, otherDocument)));
  }

  private static VersionChainCandidate candidate(
      final String versionId, final String version, final String effectiveDate) {
    return new VersionChainCandidate(
        DOCUMENT_ID,
        UUID.fromString(versionId),
        new VersionMetadataResult(
            VersionMetadataStatus.CONFIRMED,
            "Publisher",
            version,
            LocalDate.parse(effectiveDate),
            Set.of(),
            Set.of()),
        VersionChainStatus.SUPERSEDED);
  }

  private static VersionMetadataResult metadata(final String version, final String effectiveDate) {
    return new VersionMetadataResult(
        VersionMetadataStatus.CONFIRMED,
        "Publisher",
        version,
        LocalDate.parse(effectiveDate),
        Set.of(),
        Set.of());
  }

  private static VersionChainEntry entry(
      final VersionChainPlan plan, final VersionChainCandidate candidate) {
    return plan.entries().stream()
        .filter(item -> item.documentVersionId().equals(candidate.documentVersionId()))
        .findFirst()
        .orElseThrow();
  }
}
