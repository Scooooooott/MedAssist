package com.medassist.agent.api.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medassist.agent.generation.GenerationSession;
import com.medassist.agent.generation.GenerationSessionService;
import com.medassist.agent.generation.GenerationStatus;
import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GenerationControllerTest {
  private final GenerationSessionService service = mock(GenerationSessionService.class);
  private final GenerationController controller = new GenerationController(service);
  private final ExecutionContext context =
      new ExecutionContext("owner", Set.of("clinician"), "request", "trace", Map.of());
  private final GenerationCreateRequest request =
      new GenerationCreateRequest("question", null, "idempotency-key-1234");

  @AfterEach
  void clearContext() {
    ContextCarrier.clear();
  }

  @Test
  void returnsCreatedOnlyForNewSessionAndOkForIdempotentRetry() {
    final GenerationSession session = session();
    ContextCarrier.restore(context);
    when(service.create(request, context))
        .thenReturn(
            new GenerationSessionService.CreateResult(session, true),
            new GenerationSessionService.CreateResult(session, false));

    assertEquals(201, controller.create(request).getStatusCode().value());
    assertEquals(200, controller.create(request).getStatusCode().value());
  }

  private static GenerationSession session() {
    final Instant now = Instant.parse("2026-08-11T10:00:00Z");
    return new GenerationSession(
        "generation-abcdefghijkl",
        "owner",
        Set.of("clinician"),
        "v1",
        "request-hash",
        GenerationStatus.RUNNING,
        now,
        now.plusSeconds(60),
        null);
  }
}
