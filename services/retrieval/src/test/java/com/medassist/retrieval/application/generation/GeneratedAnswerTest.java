package com.medassist.retrieval.application.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeneratedAnswerTest {
  @Test
  void recordsDefensivelyCopyCitationLists() {
    final List<GeneratedCitation> citations = new ArrayList<>();
    final GeneratedAnswer answer =
        new GeneratedAnswer("answer", citations, true, TokenUsage.unknown());

    citations.add(
        new GeneratedCitation(UUID.randomUUID(), UUID.randomUUID(), "quoted", "relevant"));

    assertThat(answer.citations()).isEmpty();
    assertThatThrownBy(() -> answer.citations().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void generationEventContainsExactlyOnePayload() {
    assertThatThrownBy(() -> new GenerationEvent(null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GenerationEvent(
                    "delta", new GeneratedAnswer("a", List.of(), false, TokenUsage.unknown())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
