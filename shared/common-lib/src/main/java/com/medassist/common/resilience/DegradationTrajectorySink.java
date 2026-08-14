package com.medassist.common.resilience;

/** Receives a safe, metadata-only projection of a degradation decision. */
@FunctionalInterface
public interface DegradationTrajectorySink {
  void publish(DegradationEvent event);
}
