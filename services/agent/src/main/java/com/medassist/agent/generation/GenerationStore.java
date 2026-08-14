package com.medassist.agent.generation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface GenerationStore {
  long TERMINAL_EVENT_RESERVE_BYTES = 512L;

  CreationResult create(
      GenerationSession session, String idempotencyKey, int maxActivePerUser, int maxActiveGlobal);

  Optional<GenerationSession> find(String generationId);

  boolean transition(
      String generationId,
      Set<GenerationStatus> expected,
      GenerationStatus next,
      String terminalEventId);

  GenerationEvent append(
      GenerationEvent event,
      Set<GenerationStatus> expected,
      int maxEvents,
      long maxBufferedBytes,
      java.time.Duration retention);

  Optional<GenerationEvent> appendTerminal(
      GenerationEvent event,
      Set<GenerationStatus> expected,
      GenerationStatus terminalStatus,
      int maxEvents,
      long maxBufferedBytes,
      java.time.Duration retention);

  List<GenerationEvent> readAfter(String generationId, String lastEventId, int limit);

  record CreationResult(GenerationSession session, boolean created) {}
}
