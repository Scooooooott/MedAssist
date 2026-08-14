package com.medassist.agent.api.generation;

import com.medassist.agent.generation.GenerationSession;
import com.medassist.agent.generation.GenerationSessionService;
import com.medassist.common.context.AuthenticatedRequestContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/generations")
public final class GenerationController {
  private final GenerationSessionService service;

  public GenerationController(final GenerationSessionService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<GenerationSessionResponse> create(
      @RequestBody final GenerationCreateRequest request) {
    final GenerationSessionService.CreateResult result =
        service.create(request, AuthenticatedRequestContext.requireCurrent());
    return ResponseEntity.status(result.created() ? 201 : 200)
        .body(GenerationSessionResponse.from(result.session()));
  }

  @GetMapping("/{generationId}")
  public GenerationSessionResponse status(@PathVariable final String generationId) {
    final GenerationSession session =
        service.status(generationId, AuthenticatedRequestContext.requireCurrent());
    return GenerationSessionResponse.from(session);
  }

  @GetMapping(value = "/{generationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable final String generationId,
      @RequestHeader(name = "Last-Event-ID", required = false) final String lastEventId) {
    return service.stream(generationId, lastEventId, AuthenticatedRequestContext.requireCurrent());
  }

  @DeleteMapping("/{generationId}")
  public ResponseEntity<GenerationSessionResponse> cancel(@PathVariable final String generationId) {
    final GenerationSession session =
        service.cancel(generationId, AuthenticatedRequestContext.requireCurrent());
    return ResponseEntity.accepted().body(GenerationSessionResponse.from(session));
  }
}
