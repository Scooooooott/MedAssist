package com.medassist.auditclient.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDurableAuditOutboxTest {
  @TempDir Path directory;

  @Test
  void atomicallyPersistedMessageSurvivesRestartUntilAcknowledged() throws Exception {
    final AuditOutboxMessage message = message(1);
    final FileDurableAuditOutbox first = new FileDurableAuditOutbox(directory, 4, 1024);

    first.append(message);

    assertThat(first.size()).isEqualTo(1);
    try (Stream<Path> files = Files.list(directory)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .allMatch(name -> name.endsWith(".audit.pb"));
    }
    final FileDurableAuditOutbox restarted = new FileDurableAuditOutbox(directory, 4, 1024);
    assertThat(restarted.peek()).contains(message);

    restarted.acknowledge(message);

    assertThat(restarted.size()).isZero();
    assertThat(new FileDurableAuditOutbox(directory, 4, 1024).size()).isZero();
  }

  @Test
  void capacityAndMessageByteLimitsFailClosed() {
    final FileDurableAuditOutbox outbox = new FileDurableAuditOutbox(directory, 1, 3);
    outbox.append(message(1));

    assertThatThrownBy(() -> outbox.append(message(2)))
        .isInstanceOf(AuditOutboxFullException.class);
    final FileDurableAuditOutbox byteLimited =
        new FileDurableAuditOutbox(directory.resolve("byte-limited"), 1, 2);
    assertThatThrownBy(() -> byteLimited.append(message(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte limit");
  }

  private static AuditOutboxMessage message(final int suffix) {
    return new AuditOutboxMessage(
        UUID.nameUUIDFromBytes(new byte[] {(byte) suffix}),
        new byte[] {1, 2, 3},
        Map.of("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"));
  }
}
