package com.medassist.retrieval.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.application.AnswerService;
import com.medassist.retrieval.application.RetrievalResponseMapper;
import com.medassist.retrieval.application.RetrievalService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RetrievalControllerTest {
  @Mock private RetrievalService retrievalService;
  @Mock private RetrievalResponseMapper mapper;
  @Mock private AnswerService answerService;

  private RetrievalController controller;

  @BeforeEach
  void bindAuthenticatedContext() {
    ContextCarrier.restore(
        new ExecutionContext("subject-1", Set.of("RESEARCHER"), "request-1", "trace-1", Map.of()));
    controller = new RetrievalController(retrievalService, mapper, answerService);
  }

  @AfterEach
  void clearContext() {
    ContextCarrier.clear();
  }

  @Test
  void searchUsesAuthenticatedRoleInsteadOfRequestBodyRole() {
    when(retrievalService.search(any(SearchRequest.class))).thenReturn(null);

    controller.search(new SearchRequest("aspirin", 5, null, "ADMIN", "model", "version"));

    final ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
    verify(retrievalService).search(captured.capture());
    assertEquals("RESEARCHER", captured.getValue().role());
  }

  @Test
  void legacyAnswerEndpointIsDisabledByDefault() {
    assertEquals(
        410,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    controller.answer(
                        new AnswerRequest("question", 5, null, "ADMIN", "model", "version")))
            .getStatusCode()
            .value());
  }
}
