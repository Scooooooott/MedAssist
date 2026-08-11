package com.medassist.retrieval.application.generation;

import java.util.Objects;

/** A raw model delta or the structured answer parsed from all emitted deltas. */
public record GenerationEvent(String delta, GeneratedAnswer finalAnswer) {
  public GenerationEvent {
    if ((delta == null) == (finalAnswer == null)) {
      throw new IllegalArgumentException("exactly one generation event value is required");
    }
  }

  public static GenerationEvent delta(final String value) {
    return new GenerationEvent(Objects.requireNonNull(value, "delta"), null);
  }

  public static GenerationEvent complete(final GeneratedAnswer answer) {
    return new GenerationEvent(null, Objects.requireNonNull(answer, "answer"));
  }

  public boolean isDelta() {
    return delta != null;
  }

  public boolean isFinal() {
    return finalAnswer != null;
  }
}
