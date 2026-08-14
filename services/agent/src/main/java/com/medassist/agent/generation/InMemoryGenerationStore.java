package com.medassist.agent.generation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Deterministic test adapter with the same idempotency and transition rules as Redis. */
public final class InMemoryGenerationStore implements GenerationStore {
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, GenerationSession> sessions = new HashMap<>();
  private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
  private final Map<String, List<GenerationEvent>> events = new HashMap<>();
  private long eventSequence;

  @Override
  public CreationResult create(
      final GenerationSession session,
      final String idempotencyKey,
      final int maxActivePerUser,
      final int maxActiveGlobal) {
    lock.lock();
    try {
      final String scopedKey = session.ownerSubject() + ":" + idempotencyKey;
      final IdempotencyRecord existing = idempotency.get(scopedKey);
      if (existing != null) {
        if (!existing.requestHash().equals(session.requestHash())) {
          throw new GenerationStoreException(
              GenerationStoreException.Reason.IDEMPOTENCY_CONFLICT,
              "idempotency key was reused with a different request");
        }
        return new CreationResult(sessions.get(existing.generationId()), false);
      }
      final long globalActive =
          sessions.values().stream().filter(value -> !value.status().terminal()).count();
      final long userActive =
          sessions.values().stream()
              .filter(value -> value.ownerSubject().equals(session.ownerSubject()))
              .filter(value -> !value.status().terminal())
              .count();
      if (globalActive >= maxActiveGlobal || userActive >= maxActivePerUser) {
        throw new GenerationStoreException(
            GenerationStoreException.Reason.ACTIVE_LIMIT,
            "generation active-session limit reached");
      }
      sessions.put(session.generationId(), session);
      idempotency.put(
          scopedKey, new IdempotencyRecord(session.requestHash(), session.generationId()));
      return new CreationResult(session, true);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<GenerationSession> find(final String generationId) {
    lock.lock();
    try {
      return Optional.ofNullable(sessions.get(generationId));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean transition(
      final String generationId,
      final Set<GenerationStatus> expected,
      final GenerationStatus next,
      final String terminalEventId) {
    lock.lock();
    try {
      final GenerationSession session = sessions.get(generationId);
      if (session == null || !expected.contains(session.status())) {
        return false;
      }
      sessions.put(generationId, session.withStatus(next, terminalEventId));
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public GenerationEvent append(
      final GenerationEvent event,
      final Set<GenerationStatus> expected,
      final int maxEvents,
      final long maxBufferedBytes,
      final Duration retention) {
    lock.lock();
    try {
      final GenerationSession session = sessions.get(event.generationId());
      if (session == null || !expected.contains(session.status())) {
        throw new GenerationStateConflictException();
      }
      final List<GenerationEvent> stream =
          events.computeIfAbsent(event.generationId(), ignored -> new ArrayList<>());
      final long bytes =
          stream.stream().mapToLong(InMemoryGenerationStore::estimatedBytes).sum()
              + estimatedBytes(event);
      if (stream.size() >= maxEvents - 1
          || bytes + TERMINAL_EVENT_RESERVE_BYTES > maxBufferedBytes) {
        throw new GenerationStoreException(
            stream.size() >= maxEvents - 1
                ? GenerationStoreException.Reason.EVENT_LIMIT
                : GenerationStoreException.Reason.BYTE_LIMIT,
            "generation event buffer limit reached");
      }
      final GenerationEvent stored = event.withEventId(++eventSequence + "-0");
      stream.add(stored);
      return stored;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<GenerationEvent> appendTerminal(
      final GenerationEvent event,
      final Set<GenerationStatus> expected,
      final GenerationStatus terminalStatus,
      final int maxEvents,
      final long maxBufferedBytes,
      final Duration retention) {
    if (!terminalStatus.terminal() || !event.terminal()) {
      throw new IllegalArgumentException("terminal event and status are required");
    }
    lock.lock();
    try {
      final GenerationSession session = sessions.get(event.generationId());
      if (session == null || !expected.contains(session.status())) {
        return Optional.empty();
      }
      final List<GenerationEvent> stream =
          events.computeIfAbsent(event.generationId(), ignored -> new ArrayList<>());
      final long bytes =
          stream.stream().mapToLong(InMemoryGenerationStore::estimatedBytes).sum()
              + estimatedBytes(event);
      if (stream.size() >= maxEvents || bytes > maxBufferedBytes) {
        throw new GenerationStoreException(
            stream.size() >= maxEvents
                ? GenerationStoreException.Reason.EVENT_LIMIT
                : GenerationStoreException.Reason.BYTE_LIMIT,
            "generation event buffer limit reached");
      }
      final GenerationEvent stored = event.withEventId(++eventSequence + "-0");
      stream.add(stored);
      sessions.put(event.generationId(), session.withStatus(terminalStatus, stored.eventId()));
      return Optional.of(stored);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<GenerationEvent> readAfter(
      final String generationId, final String lastEventId, final int limit) {
    lock.lock();
    try {
      return events.getOrDefault(generationId, List.of()).stream()
          .filter(event -> compareIds(event.eventId(), lastEventId) > 0)
          .sorted(Comparator.comparingLong(InMemoryGenerationStore::sequence))
          .limit(limit)
          .toList();
    } finally {
      lock.unlock();
    }
  }

  private static long estimatedBytes(final GenerationEvent event) {
    return event.payload().toString().getBytes(StandardCharsets.UTF_8).length + 128L;
  }

  private static int compareIds(final String left, final String right) {
    if (right == null || right.isBlank() || "0-0".equals(right)) {
      return 1;
    }
    return Long.compare(sequence(left), sequence(right));
  }

  private static long sequence(final GenerationEvent event) {
    return sequence(event.eventId());
  }

  private static long sequence(final String id) {
    return Long.parseLong(id.substring(0, id.indexOf('-')));
  }

  private record IdempotencyRecord(String requestHash, String generationId) {}
}
