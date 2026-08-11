package com.medassist.retrieval.application.generation;

import java.util.List;
import java.util.Objects;

public record GeneratedAnswer(
    String answer,
    List<GeneratedCitation> citations,
    boolean sufficientEvidence,
    TokenUsage tokenUsage) {
  public GeneratedAnswer {
    Objects.requireNonNull(answer, "answer");
    citations = List.copyOf(Objects.requireNonNull(citations, "citations"));
    tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage");
  }
}
