package com.medassist.auditgovernance.transport;

import java.util.Optional;

/** FIFO boundary for a bounded local buffer. Implementations must never evict silently. */
public interface DurableAuditEventBuffer {
  boolean offer(BufferedAuditMessage message);

  Optional<BufferedAuditMessage> peek();

  void acknowledge(BufferedAuditMessage message);

  int size();

  int capacity();
}
