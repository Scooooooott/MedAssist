package com.medassist.retrieval.api;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.api.dto.SearchResponse;
import com.medassist.retrieval.application.AnswerService;
import com.medassist.retrieval.application.AnswerStreamEvent;
import com.medassist.retrieval.application.RetrievalResponseMapper;
import com.medassist.retrieval.application.RetrievalService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@RestController
@RequestMapping("/api")
public class RetrievalController {
  private final RetrievalService retrievalService;
  private final RetrievalResponseMapper mapper;
  private final AnswerService answerService;

  public RetrievalController(
      final RetrievalService retrievalService,
      final RetrievalResponseMapper mapper,
      final AnswerService answerService) {
    this.retrievalService = retrievalService;
    this.mapper = mapper;
    this.answerService = answerService;
  }

  @PostMapping("/search")
  public SearchResponse search(@RequestBody final SearchRequest request) {
    return mapper.toResponse(retrievalService.search(request));
  }

  @PostMapping("/answer")
  public AnswerResponse answer(@RequestBody final AnswerRequest request) {
    return answerService.answer(request);
  }

  @PostMapping(path = "/answer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamAnswer(@RequestBody final AnswerRequest request) {
    final SseEmitter emitter = new SseEmitter(60_000L);
    final AtomicReference<Disposable> subscription = new AtomicReference<>();
    subscription.set(
        answerService.stream(request)
            .subscribe(
                event -> send(emitter, event),
                ignored -> sendErrorAndComplete(emitter),
                emitter::complete));
    emitter.onCompletion(() -> dispose(subscription));
    emitter.onTimeout(
        () -> {
          dispose(subscription);
          sendErrorAndComplete(emitter);
        });
    return emitter;
  }

  private void send(final SseEmitter emitter, final AnswerStreamEvent event) {
    try {
      if (event.isDelta()) {
        emitter.send(SseEmitter.event().name("delta").data(Map.of("delta", event.delta())));
      } else if (event.isRetry()) {
        emitter.send(SseEmitter.event().name("retry").data(event.retryStatus()));
      } else {
        emitter.send(SseEmitter.event().name("final").data(event.finalResponse()));
      }
    } catch (final IOException exception) {
      throw new UncheckedIOException("Unable to write answer stream", exception);
    }
  }

  private void sendErrorAndComplete(final SseEmitter emitter) {
    try {
      emitter.send(
          SseEmitter.event()
              .name("error")
              .data(Map.of("message", "The answer stream could not be completed.")));
      emitter.complete();
    } catch (final IOException ignored) {
      emitter.complete();
    }
  }

  private void dispose(final AtomicReference<Disposable> subscription) {
    final Disposable disposable = subscription.getAndSet(null);
    if (disposable != null && !disposable.isDisposed()) {
      disposable.dispose();
    }
  }
}
