package com.medassist.auditgovernance.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medassist.auditgovernance.AuditEvent;
import com.medassist.auditgovernance.AuditPayload;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAuditChainStoreTest {
  @TempDir Path directory;

  @Test
  void restoresChainAndProcessedIdsAcrossInstances() {
    final AuditEvent event = event(1);
    final FileAuditChainStore first = new FileAuditChainStore(directory, "chain.bin", 65_536);
    final AuditEvent sealed = first.publish(event);

    final FileAuditChainStore restored = new FileAuditChainStore(directory, "chain.bin", 65_536);

    assertThat(restored.events()).containsExactly(sealed);
    assertThat(restored.contains(event.eventId())).isTrue();
    restored.markProcessed(event.eventId());
    assertThat(restored.verify().valid()).isTrue();
    assertThatThrownBy(() -> restored.publish(event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already present");
  }

  @Test
  void corruptedRecordFailsClosedDuringRecovery() throws Exception {
    final FileAuditChainStore store = new FileAuditChainStore(directory, "chain.bin", 65_536);
    store.publish(event(1));
    final Path file = store.file();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.position(16);
      channel.write(ByteBuffer.wrap(new byte[] {(byte) 0xFF}));
      channel.force(true);
    }

    assertThatThrownBy(() -> new FileAuditChainStore(directory, "chain.bin", 65_536))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be recovered safely");
    assertThatThrownBy(() -> store.publish(event(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed or became invalid");
  }

  @Test
  void truncatedRecordFailsClosedDuringRecovery() throws Exception {
    final FileAuditChainStore store = new FileAuditChainStore(directory, "chain.bin", 65_536);
    store.publish(event(1));
    final Path file = store.file();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.truncate(Files.size(file) - 1);
      channel.force(true);
    }

    assertThatThrownBy(() -> new FileAuditChainStore(directory, "chain.bin", 65_536))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be recovered safely");
  }

  @Test
  void oneHundredConcurrentAppendsRemainIntact() throws Exception {
    final FileAuditChainStore store = new FileAuditChainStore(directory, "chain.bin", 65_536);
    final int eventCount = 100;
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(16);
    final List<Future<?>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < eventCount; index++) {
        final int eventIndex = index;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  store.publish(event(eventIndex));
                  return null;
                }));
      }
      start.countDown();
      for (final Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(store.events()).hasSize(eventCount);
    assertThat(store.verify().valid()).isTrue();
    final FileAuditChainStore restored = new FileAuditChainStore(directory, "chain.bin", 65_536);
    assertThat(restored.events()).hasSize(eventCount);
    assertThat(restored.verify().valid()).isTrue();
  }

  @Test
  void markProcessedCannotDriftFromDurableChain() {
    final FileAuditChainStore store = new FileAuditChainStore(directory, "chain.bin", 65_536);

    assertThatThrownBy(() -> store.markProcessed(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not durable");
  }

  private static AuditEvent event(final int index) {
    return new AuditEvent(
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index),
        "audit-source",
        "SYSTEM",
        "AUDIT",
        "audit_event",
        "resource-" + index,
        "ALLOWED",
        AuditPayload.of(Map.of("entityCount", Integer.toString(index + 1))));
  }
}
