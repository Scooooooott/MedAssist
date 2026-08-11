package com.medassist.retrieval.application.generation;

import com.medassist.retrieval.application.model.RetrievedChunk;
import java.util.List;
import reactor.core.publisher.Flux;

/** Fail-closed generator used when no explicitly enabled LLM client is configured. */
public final class UnavailableAnswerGenerator implements AnswerGenerator {
  @Override
  public GeneratedAnswer generate(final String query, final List<RetrievedChunk> evidence) {
    throw new AnswerGenerationException();
  }

  @Override
  public Flux<GenerationEvent> stream(final String query, final List<RetrievedChunk> evidence) {
    return Flux.error(new AnswerGenerationException());
  }
}
