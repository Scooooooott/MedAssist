package com.medassist.agent.generation;

/** Internal signal used when a concurrent terminal transition wins an event append race. */
final class GenerationStateConflictException extends RuntimeException {
  GenerationStateConflictException() {
    super("generation state no longer accepts events");
  }
}
