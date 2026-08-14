package com.medassist.agent.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class GenerationStoreTest {
  @Test
  void terminalAppendAtomicallyTransitionsOnlyOnce() {
    final InMemoryGenerationStore store = new InMemoryGenerationStore();
    final GenerationSession session = session("generation-abcdefghijkl");
    store.create(session, "idempotency-key", 1, 1);
    assertTrue(
        store.transition(
            session.generationId(),
            Set.of(GenerationStatus.CREATED),
            GenerationStatus.RUNNING,
            null));
    final GenerationEvent terminal =
        new GenerationEvent(
            null,
            session.generationId(),
            GenerationEventType.FINAL,
            "1",
            Instant.now(),
            Map.of("status", "COMPLETED"));

    final GenerationEvent stored =
        store
            .appendTerminal(
                terminal,
                Set.of(GenerationStatus.RUNNING),
                GenerationStatus.COMPLETED,
                10,
                10_000,
                Duration.ofMinutes(1))
            .orElseThrow();

    assertFalse(
        store
            .appendTerminal(
                terminal,
                Set.of(GenerationStatus.RUNNING),
                GenerationStatus.COMPLETED,
                10,
                10_000,
                Duration.ofMinutes(1))
            .isPresent());
    assertEquals(
        stored.eventId(), store.find(session.generationId()).orElseThrow().terminalEventId());
    assertThrows(
        GenerationStateConflictException.class,
        () ->
            store.append(
                new GenerationEvent(
                    null,
                    session.generationId(),
                    GenerationEventType.DELTA,
                    "1",
                    Instant.now(),
                    Map.of("text", "late")),
                Set.of(GenerationStatus.RUNNING),
                10,
                10_000,
                Duration.ofMinutes(1)));
  }

  @Test
  void redisFailureIsExplicitAndNeverFallsBackToMemory() {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("unavailable"));
    final RedisGenerationStore store =
        new RedisGenerationStore(redis, new ObjectMapper(), "test", Duration.ofMinutes(1));

    final GenerationStoreException failure =
        assertThrows(GenerationStoreException.class, () -> store.find("generation-id"));

    assertEquals(GenerationStoreException.Reason.UNAVAILABLE, failure.reason());
  }

  @Test
  void regularEventsReserveCountAndBytesForReplayableTerminal() {
    final InMemoryGenerationStore eventStore = runningStore("generation-event-limit");
    append(eventStore, "generation-event-limit", "accepted", 3, 10_000);
    append(eventStore, "generation-event-limit", "delta", 3, 10_000);
    assertEquals(
        GenerationStoreException.Reason.EVENT_LIMIT,
        assertThrows(
                GenerationStoreException.class,
                () -> append(eventStore, "generation-event-limit", "overflow", 3, 10_000))
            .reason());
    assertTerminalSucceeds(eventStore, "generation-event-limit", 3, 10_000);

    final InMemoryGenerationStore byteStore = runningStore("generation-byte-limit");
    append(byteStore, "generation-byte-limit", "accepted", 10, 1024);
    append(byteStore, "generation-byte-limit", "x".repeat(200), 10, 1024);
    assertEquals(
        GenerationStoreException.Reason.BYTE_LIMIT,
        assertThrows(
                GenerationStoreException.class,
                () -> append(byteStore, "generation-byte-limit", "x".repeat(100), 10, 1024))
            .reason());
    assertTerminalSucceeds(byteStore, "generation-byte-limit", 10, 1024);
  }

  private static InMemoryGenerationStore runningStore(final String generationId) {
    final InMemoryGenerationStore store = new InMemoryGenerationStore();
    store.create(session(generationId), "idempotency-" + generationId, 2, 2);
    assertTrue(
        store.transition(
            generationId, Set.of(GenerationStatus.CREATED), GenerationStatus.RUNNING, null));
    return store;
  }

  private static void append(
      final InMemoryGenerationStore store,
      final String generationId,
      final String text,
      final int maxEvents,
      final long maxBytes) {
    store.append(
        new GenerationEvent(
            null,
            generationId,
            GenerationEventType.DELTA,
            "1",
            Instant.now(),
            Map.of("text", text)),
        Set.of(GenerationStatus.RUNNING),
        maxEvents,
        maxBytes,
        Duration.ofMinutes(1));
  }

  private static void assertTerminalSucceeds(
      final InMemoryGenerationStore store,
      final String generationId,
      final int maxEvents,
      final long maxBytes) {
    assertTrue(
        store
            .appendTerminal(
                new GenerationEvent(
                    null,
                    generationId,
                    GenerationEventType.ERROR,
                    "1",
                    Instant.now(),
                    Map.of("code", "LIMIT")),
                Set.of(GenerationStatus.RUNNING),
                GenerationStatus.FAILED,
                maxEvents,
                maxBytes,
                Duration.ofMinutes(1))
            .isPresent());
    assertEquals(GenerationStatus.FAILED, store.find(generationId).orElseThrow().status());
  }

  private static GenerationSession session(final String generationId) {
    final Instant now = Instant.now();
    return new GenerationSession(
        generationId,
        "owner",
        Set.of("clinician"),
        "v1",
        "request-hash",
        GenerationStatus.CREATED,
        now,
        now.plusSeconds(60),
        null);
  }
}
