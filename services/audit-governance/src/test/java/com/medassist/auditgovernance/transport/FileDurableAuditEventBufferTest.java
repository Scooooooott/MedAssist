package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDurableAuditEventBufferTest {
  @TempDir Path temporaryDirectory;

  @Test
  void persistsFifoEntriesAcrossBufferRecreation() {
    final ObjectMapper objectMapper = new ObjectMapper();
    final BufferedAuditMessage first = message("event-1", "protobuf-one");
    final BufferedAuditMessage second = message("event-2", "protobuf-two");
    final FileDurableAuditEventBuffer initial =
        new FileDurableAuditEventBuffer(temporaryDirectory, 2, 1024, objectMapper);

    assertThat(initial.offer(first)).isTrue();
    assertThat(initial.offer(second)).isTrue();
    assertThat(initial.offer(message("event-3", "protobuf-three"))).isFalse();

    final FileDurableAuditEventBuffer restored =
        new FileDurableAuditEventBuffer(temporaryDirectory, 2, 1024, objectMapper);
    assertThat(restored.peek()).contains(first);
    restored.acknowledge(first);
    assertThat(restored.peek()).contains(second);
  }

  private static BufferedAuditMessage message(final String eventId, final String payload) {
    return new BufferedAuditMessage(eventId, payload.getBytes(StandardCharsets.UTF_8));
  }
}
