package com.medassist.retrieval.api;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.api.dto.SearchResponse;
import com.medassist.retrieval.application.AnswerService;
import com.medassist.retrieval.application.RetrievalResponseMapper;
import com.medassist.retrieval.application.RetrievalService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    final SseEmitter emitter = new SseEmitter();
    try {
      emitter.send(SseEmitter.event().name("answer").data(answerService.answer(request)));
      emitter.complete();
    } catch (final Exception exception) {
      emitter.completeWithError(exception);
    }
    return emitter;
  }
}
