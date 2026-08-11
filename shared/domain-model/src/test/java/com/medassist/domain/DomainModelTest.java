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

  @Test
  void coreDocumentAndRetrievalRecordsEnforceTheirContracts() {
    final UUID documentId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();
    final UUID chunkId = UUID.randomUUID();
    final Document document =
        new Document(
            documentId, "minio", "s3://bucket/doc", DocType.GUIDELINE, "Publisher", "Title");
    final Chunk chunk =
        new Chunk(
            chunkId,
            versionId,
            0,
            "1",
            "Deidentified text",
            2,
            new SourceRange(0, 17),
            Map.of("content_domain", ContentDomain.PUBLIC.name()));
    final RetrievalResult retrieval = new RetrievalResult(chunk, 0.8D, RetrievalMethod.HYBRID);
    final Citation citation = new Citation(chunkId, versionId, 0, 5, "sha256:quoted");
    final Answer answer = new Answer("Answer", List.of(citation), 0.9D, false, "");

    assertEquals(documentId, document.id());
    assertEquals(chunk, retrieval.chunk());
    assertEquals(List.of(citation), answer.citations());
    assertThrows(UnsupportedOperationException.class, () -> answer.citations().add(citation));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Citation(chunkId, versionId, 7, 2, "sha256:quoted"));
    assertThrows(
        IllegalArgumentException.class, () -> new Answer("Answer", List.of(), 1.1D, false, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Chunk(chunkId, versionId, -1, "1", "text", 1, new SourceRange(0, 4), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Chunk(chunkId, versionId, 0, "1", "text", -1, new SourceRange(0, 4), Map.of()));
  }

  @Test
  void auditEventContainsOnlyIdentifiersAndHashes() {
    final UUID eventId = UUID.randomUUID();
    final Instant timestamp = Instant.now();
    final AuditEvent event =
        new AuditEvent(
            eventId,
            timestamp,
            "synthetic-user",
            Role.RESEARCHER,
            "SEARCH",
            "DOCUMENT",
            "doc-1",
            "ALLOWED",
            "sha256:payload",
            "sha256:previous");

    assertEquals(eventId, event.eventId());
    assertEquals("sha256:payload", event.payloadHash());
    assertThrows(
        NullPointerException.class,
        () ->
            new AuditEvent(
                eventId,
                timestamp,
                null,
                Role.RESEARCHER,
                "SEARCH",
                "DOCUMENT",
                "doc-1",
                "ALLOWED",
                "sha256:payload",
                null));
  }

  @Test
  void rangesSectionsAndTablesPreserveSourceStructure() {
    final SourceRange first = new SourceRange(0, 10);
    final SourceRange overlap = new SourceRange(5, 12);
    final SourceRange separate = new SourceRange(10, 20);
    assertTrue(first.overlaps(overlap));
    assertFalse(first.overlaps(separate));
    assertThrows(IllegalArgumentException.class, () -> new SourceRange(-1, 2));

    final Section child = new Section("1.1", "Child", 1, "child text", List.of());
    final Section parent =
        new Section("1", "Parent", 0, "parent text", List.of(child), new SourceRange(0, 11));
    assertEquals(List.of(child), parent.children());
    assertThrows(
        IllegalArgumentException.class, () -> new Section("1", "Invalid", -1, "text", List.of()));

    final TableBlock defaults = new TableBlock(null, List.of("Dose"), List.of(Map.of("Dose", "5")));
    final TableBlock explicit =
        new TableBlock(
            "1",
            "Dose table",
            List.of("Dose"),
            List.of(Map.of("Dose", "5")),
            "Dose: 5",
            new SourceRange(20, 27));
    assertEquals("", defaults.sectionPath());
    assertEquals(new SourceRange(0, 0), defaults.sourceRange());
    assertEquals("Dose: 5", explicit.linearizedText());
    assertThrows(UnsupportedOperationException.class, () -> explicit.headers().add("Unit"));
  }

  @Test
  void versionFreshnessAndPhiScoresCoverBothBoundaries() {
    final DocumentVersion futureVersion =
        new DocumentVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "future",
            "sha256:future",
            LocalDate.now().plusDays(1),
            Instant.now(),
            DocumentStatus.ACTIVE,
            null,
            "s3://bucket/future");
    assertFalse(futureVersion.isCurrentlyEffective());
    assertFalse(futureVersion.isStale(Duration.ofDays(1)));
    assertThrows(NullPointerException.class, () -> futureVersion.isStale(null));
    assertThrows(
        IllegalArgumentException.class, () -> new PhiEntity("PERSON", 0, 1, -0.1D, "presidio"));
    assertThrows(
        IllegalArgumentException.class, () -> new PhiEntity("PERSON", 0, 1, 1.1D, "presidio"));
  }

  @Test
  void policyEnumsExposeEveryDeclaredValue() {
    assertEquals(4, ColumnClassification.values().length);
    assertEquals(5, ContentDomain.values().length);
    assertEquals(5, DocType.values().length);
    assertEquals(3, DocumentStatus.values().length);
    assertEquals(4, RetrievalMethod.values().length);
    assertEquals(3, Role.values().length);
  }
}
