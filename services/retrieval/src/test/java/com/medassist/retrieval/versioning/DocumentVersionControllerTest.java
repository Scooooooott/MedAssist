package com.medassist.retrieval.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.retrieval.config.RetrievalProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentVersionControllerTest {
  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID FROM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID TO_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void historyUsesConfiguredStalenessWindow() {
    final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
    final RetrievalProperties properties = new RetrievalProperties();
    properties.setStalenessYears(4);
    final List<DocumentVersionView> history =
        List.of(
            new DocumentVersionView(
                FROM_ID,
                DOCUMENT_ID,
                "v1",
                LocalDate.of(2025, 1, 1),
                "ACTIVE",
                null,
                "Publisher",
                false));
    when(repository.history(DOCUMENT_ID, 4)).thenReturn(history);

    final DocumentVersionController controller =
        new DocumentVersionController(repository, new ChunkVersionDiffer(), properties);

    assertThat(controller.history(DOCUMENT_ID)).isEqualTo(history);
  }

  @Test
  void blankStrategyUsesDefaultAndExplicitStrategyIsTrimmed() {
    final DocumentVersionRepository repository = mock(DocumentVersionRepository.class);
    final RetrievalProperties properties = new RetrievalProperties();
    properties.setDefaultChunkingStrategyId("structure-v2");
    final VersionChunk before = new VersionChunk(FROM_ID, 0, "Summary", "old text");
    final VersionChunk after = new VersionChunk(TO_ID, 0, "Summary", "new text");
    when(repository.chunks(DOCUMENT_ID, FROM_ID, "structure-v2")).thenReturn(List.of(before));
    when(repository.chunks(DOCUMENT_ID, TO_ID, "structure-v2")).thenReturn(List.of(after));
    when(repository.chunks(DOCUMENT_ID, FROM_ID, "semantic-v1")).thenReturn(List.of(before));
    when(repository.chunks(DOCUMENT_ID, TO_ID, "semantic-v1")).thenReturn(List.of(after));
    final DocumentVersionController controller =
        new DocumentVersionController(repository, new ChunkVersionDiffer(), properties);

    final VersionDiffResponse defaultDiff = controller.diff(DOCUMENT_ID, FROM_ID, TO_ID, "  ");
    final VersionDiffResponse explicitDiff =
        controller.diff(DOCUMENT_ID, FROM_ID, TO_ID, " semantic-v1 ");

    assertThat(defaultDiff.differences())
        .singleElement()
        .extracting(ChunkDifference::changeType)
        .isEqualTo("CHANGED");
    assertThat(explicitDiff.differences()).hasSize(1);
    verify(repository).chunks(DOCUMENT_ID, FROM_ID, "structure-v2");
    verify(repository).chunks(DOCUMENT_ID, TO_ID, "semantic-v1");
  }
}
