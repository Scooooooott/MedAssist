package com.medassist.common.resilience;

/** Publishes a metadata-only degradation event to the configured audit transport. */
@FunctionalInterface
public interface DegradationAuditSink {
  void publish(DegradationEvent event);
}
