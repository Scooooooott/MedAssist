package com.medassist.retrieval.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentVersionControllerTest {
  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID FROM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID TO_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void historyDelegatesToService() {
    final DocumentVersionService service = mock(DocumentVersionService.class);
    final List<DocumentVersionView> history =
        List.of(
            new DocumentVersionView(
                FROM_ID, DOCUMENT_ID, "v1", null, "ACTIVE", null, "Publisher", null));
    when(service.history(DOCUMENT_ID)).thenReturn(history);

    final DocumentVersionController controller = new DocumentVersionController(service);

    assertThat(controller.history(DOCUMENT_ID)).isEqualTo(history);
    verify(service).history(DOCUMENT_ID);
  }

  @Test
  void diffDelegatesToService() {
    final DocumentVersionService service = mock(DocumentVersionService.class);
    final VersionDiffResponse response =
        new VersionDiffResponse(DOCUMENT_ID, FROM_ID, TO_ID, List.of());
    when(service.diff(DOCUMENT_ID, FROM_ID, TO_ID, " semantic-v1 ")).thenReturn(response);
    final DocumentVersionController controller = new DocumentVersionController(service);

    assertThat(controller.diff(DOCUMENT_ID, FROM_ID, TO_ID, " semantic-v1 ")).isEqualTo(response);
    verify(service).diff(DOCUMENT_ID, FROM_ID, TO_ID, " semantic-v1 ");
  }
}
