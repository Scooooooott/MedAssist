package com.medassist.retrieval.application.generation;

import com.medassist.retrieval.application.model.RetrievedChunk;
import java.util.List;
import reactor.core.publisher.Flux;

/** Provider-neutral boundary for answer generation over already retrieved evidence. */
public interface AnswerGenerator {
  GeneratedAnswer generate(String query, List<RetrievedChunk> evidence);

  Flux<GenerationEvent> stream(String query, List<RetrievedChunk> evidence);
}
