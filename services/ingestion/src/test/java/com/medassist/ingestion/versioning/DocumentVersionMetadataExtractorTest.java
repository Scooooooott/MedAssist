package com.medassist.ingestion.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.domain.DocumentIR;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentVersionMetadataExtractorTest {
  private final DocumentVersionMetadataExtractor extractor = new DocumentVersionMetadataExtractor();

  @Test
  void missingMetadataIsUnknownAndListsEveryMissingField() {
    final VersionMetadataResult result =
        extractor.extract(new DocumentIR(List.of(), List.of(), Map.of("publisher", "Publisher")));

    assertEquals(VersionMetadataStatus.UNKNOWN, result.status());
    assertEquals(
        Set.of(VersionMetadataField.VERSION, VersionMetadataField.EFFECTIVE_DATE),
        result.missingFields());
    assertTrue(result.invalidFields().isEmpty());
    assertEquals(
        Set.of(VersionMetadataField.VERSION, VersionMetadataField.EFFECTIVE_DATE),
        result.issueFields());
  }

  @Test
  void invalidDateIsUnknownAndNeverNormalized() {
    final VersionMetadataResult result =
        extractor.extract(
            new DocumentIR(
                List.of(),
                List.of(),
                Map.of("publisher", "Publisher", "version", "v1", "effective_date", "2024-02-30")));

    assertEquals(VersionMetadataStatus.UNKNOWN, result.status());
    assertEquals(Set.of(VersionMetadataField.EFFECTIVE_DATE), result.invalidFields());
    assertTrue(result.missingFields().isEmpty());
    assertNull(result.effectiveDate());
  }

  @Test
  void reviewCommandOnlyAcceptsUnknownMetadata() {
    final UUID documentId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();
    final VersionMetadataResult unknown =
        extractor.extract(new DocumentIR(List.of(), List.of(), Map.of("publisher", "Publisher")));

    final VersionReviewQueueCommand command =
        VersionReviewQueueCommand.from(documentId, versionId, unknown);

    assertEquals(documentId, command.documentId());
    assertEquals(versionId, command.documentVersionId());
    assertEquals(unknown.issueFields(), command.issueFields());
    assertThrows(
        IllegalArgumentException.class,
        () -> VersionReviewQueueCommand.from(documentId, versionId, confirmed("v1", "2024-01-01")));
  }

  private static VersionMetadataResult confirmed(final String version, final String effectiveDate) {
    return new VersionMetadataResult(
        VersionMetadataStatus.CONFIRMED,
        "Publisher",
        version,
        LocalDate.parse(effectiveDate),
        Set.of(),
        Set.of());
  }
}
