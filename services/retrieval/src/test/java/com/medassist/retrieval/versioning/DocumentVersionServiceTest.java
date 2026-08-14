package com.medassist.retrieval.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.retrieval.config.RetrievalProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentVersionServiceTest {
  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID FROM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID TO_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void historyComputesStalenessFromEffectiveDateAndPreservesUnknown() {
    final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
    final RetrievalProperties properties = new RetrievalProperties();
    properties.setStalenessYears(4);
    when(repository.history(DOCUMENT_ID))
        .thenReturn(
            List.of(
                view(LocalDate.of(2022, 8, 13), false),
                view(LocalDate.of(2022, 8, 14), true),
                view(null, false)));
    final DocumentVersionService service =
        new DocumentVersionService(
            repository,
            new ChunkVersionDiffer(),
            properties,
            Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC));

    assertThat(service.history(DOCUMENT_ID).stream().map(DocumentVersionView::stale).toList())
        .containsExactly(true, false, null);
  }

  @Test
  void diffUsesDefaultStrategyForBlankInputAndTrimsExplicitStrategy() {
    final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
    final RetrievalProperties properties = new RetrievalProperties();
    properties.setDefaultChunkingStrategyId("structure-v2");
    final VersionChunk before = new VersionChunk(FROM_ID, 0, "Summary", "old text");
    final VersionChunk after = new VersionChunk(TO_ID, 0, "Summary", "new text");
    when(repository.chunks(DOCUMENT_ID, FROM_ID, "structure-v2")).thenReturn(List.of(before));
    when(repository.chunks(DOCUMENT_ID, TO_ID, "structure-v2")).thenReturn(List.of(after));
    when(repository.chunks(DOCUMENT_ID, FROM_ID, "semantic-v1")).thenReturn(List.of(before));
    when(repository.chunks(DOCUMENT_ID, TO_ID, "semantic-v1")).thenReturn(List.of(after));
    final DocumentVersionService service =
        new DocumentVersionService(
            repository, new ChunkVersionDiffer(), properties, Clock.systemUTC());

    assertThat(service.diff(DOCUMENT_ID, FROM_ID, TO_ID, "  ").differences())
        .singleElement()
        .extracting(ChunkDifference::changeType)
        .isEqualTo("CHANGED");
    assertThat(service.diff(DOCUMENT_ID, FROM_ID, TO_ID, " semantic-v1 ").differences()).hasSize(1);
    verify(repository).chunks(DOCUMENT_ID, FROM_ID, "structure-v2");
    verify(repository).chunks(DOCUMENT_ID, TO_ID, "semantic-v1");
  }

  private DocumentVersionView view(final LocalDate effectiveDate, final Boolean stale) {
    return new DocumentVersionView(
        UUID.randomUUID(), DOCUMENT_ID, "v1", effectiveDate, "ACTIVE", null, "Publisher", stale);
  }
}
