package com.medassist.auditclient.outbox;

import java.util.Optional;

/** Ordered durable handoff between service publication and Kafka delivery. */
public interface AuditOutbox {
  void append(AuditOutboxMessage message);

  Optional<AuditOutboxMessage> peek();

  void acknowledge(AuditOutboxMessage message);

  int size();

  int capacity();
}
