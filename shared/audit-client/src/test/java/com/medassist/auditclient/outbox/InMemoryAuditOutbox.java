package com.medassist.auditclient.outbox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** Test-only bounded outbox with the same FIFO acknowledgement contract as the file store. */
public final class InMemoryAuditOutbox implements AuditOutbox {
  private final int capacity;
  private final Deque<AuditOutboxMessage> messages = new ArrayDeque<>();

  public InMemoryAuditOutbox(final int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  @Override
  public synchronized void append(final AuditOutboxMessage message) {
    if (messages.size() >= capacity) {
      throw new AuditOutboxFullException("audit outbox is full");
    }
    messages.addLast(message);
  }

  @Override
  public synchronized Optional<AuditOutboxMessage> peek() {
    return Optional.ofNullable(messages.peekFirst());
  }

  @Override
  public synchronized void acknowledge(final AuditOutboxMessage message) {
    if (!message.equals(messages.peekFirst())) {
      throw new IllegalStateException("only the audit outbox head can be acknowledged");
    }
    messages.removeFirst();
  }

  @Override
  public synchronized int size() {
    return messages.size();
  }

  @Override
  public int capacity() {
    return capacity;
  }
}
