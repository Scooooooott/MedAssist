package com.medassist.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainModelTest {
  @Test
  void documentVersionReportsEffectiveAndStaleState() {
    final DocumentVersion version =
        new DocumentVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "2026-01",
            "sha256:abc",
            LocalDate.now().minusDays(1),
            Instant.now().minus(Duration.ofDays(10)),
            DocumentStatus.ACTIVE,
            null,
            "s3://raw-documents/doc.pdf");

    assertTrue(version.isCurrentlyEffective());
    assertTrue(version.isStale(Duration.ofDays(1)));
  }

  @Test
  void withdrawnDocumentVersionIsNotCurrentlyEffective() {
    final DocumentVersion version =
        new DocumentVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "2026-01",
            "sha256:abc",
            LocalDate.now().minusDays(1),
            Instant.now(),
            DocumentStatus.WITHDRAWN,
            null,
            "s3://raw-documents/doc.pdf");

    assertFalse(version.isCurrentlyEffective());
  }

  @Test
  void phiEntityDoesNotStoreRawValueAndValidatesSpan() {
    final PhiEntity entity = new PhiEntity("PERSON", 4, 12, 0.98D, "presidio");

    assertEquals("PERSON", entity.entityType());
    assertThrows(IllegalArgumentException.class, () -> new PhiEntity("PERSON", 8, 2, 0.5D, "x"));
  }

  @Test
  void collectionFieldsAreDefensivelyCopied() {
    final Section section = new Section("1", "Intro", 1, "Text", List.of());
    final DocumentIR ir = new DocumentIR(List.of(section), List.of(), Map.of("source", "test"));

    assertThrows(UnsupportedOperationException.class, () -> ir.sections().add(section));
  }
}
