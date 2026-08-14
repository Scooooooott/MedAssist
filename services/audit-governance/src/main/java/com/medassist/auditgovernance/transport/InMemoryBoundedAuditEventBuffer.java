package com.medassist.auditgovernance.transport;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;

/** Bounded non-durable implementation intended for tests and explicit local-only use. */
public final class InMemoryBoundedAuditEventBuffer implements DurableAuditEventBuffer {
  private final ArrayBlockingQueue<BufferedAuditMessage> messages;

  public InMemoryBoundedAuditEventBuffer(final int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    messages = new ArrayBlockingQueue<>(capacity);
  }

  @Override
  public boolean offer(final BufferedAuditMessage message) {
    return messages.offer(Objects.requireNonNull(message, "message"));
  }

  @Override
  public Optional<BufferedAuditMessage> peek() {
    return Optional.ofNullable(messages.peek());
  }

  @Override
  public void acknowledge(final BufferedAuditMessage message) {
    final BufferedAuditMessage head = messages.peek();
    if (!Objects.equals(head, message) || !messages.remove(message)) {
      throw new IllegalStateException("only the current audit buffer head can be acknowledged");
    }
  }

  @Override
  public int size() {
    return messages.size();
  }

  @Override
  public int capacity() {
    return messages.remainingCapacity() + messages.size();
  }
}
