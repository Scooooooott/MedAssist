package com.medassist.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DegradationRecorderTest {
  @Test
  void recordedEventHasNoQueryOrChunkTextSurface() {
    final List<DegradationEvent> events = new ArrayList<>();
    final DegradationRecorder recorder = events::add;
    final Degradation degradation =
        new Degradation(
            "LEXICAL_CHANNEL_FAILED",
            "LEXICAL_RETRIEVAL",
            FallbackMode.VECTOR_RESULTS,
            "lexical retrieval unavailable; vector results retained");

    recorder.record(ResilienceComponent.LEXICAL_RETRIEVAL, degradation);

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().toString()).doesNotContain("patient query", "Evidence chunk text");
    assertThat(
            Arrays.stream(DegradationEvent.class.getRecordComponents())
                .map(RecordComponent::getName))
        .doesNotContain("query", "rawQuery", "chunk", "chunkText", "text");
  }
}
