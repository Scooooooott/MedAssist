package com.medassist.domain;

import java.util.List;
import java.util.Objects;

public record Answer(
    String text,
    List<Citation> citations,
    double confidence,
    boolean abstained,
    String abstainReason) {
  public Answer {
    Objects.requireNonNull(text, "text");
    citations = List.copyOf(citations);
    if (confidence < 0.0D || confidence > 1.0D) {
      throw new IllegalArgumentException("confidence must be within [0, 1]");
    }
  }
}
