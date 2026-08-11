package com.medassist.retrieval.application.generation;

public class AnswerGenerationException extends RuntimeException {
  public AnswerGenerationException() {
    super("Answer generation failed");
  }
}
