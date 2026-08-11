package com.medassist.auditgovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AuditGovernanceDomainTest {
  @Test
  void canonicalSerializationIsDeterministic() {
    final AuditPayload firstPayload =
        AuditPayload.of(Map.of("traceId", "trace-1", "entityCount", "2"));
    final AuditPayload secondPayload =
        AuditPayload.of(Map.of("entityCount", "2", "traceId", "trace-1"));
    final AuditEvent first = event(firstPayload);
    final AuditEvent second =
        new AuditEvent(
            first.eventId(),
            first.timestamp(),
            first.actor(),
            first.category(),
            first.role(),
            first.action(),
            first.resourceType(),
            first.resourceId(),
            first.outcome(),
            secondPayload,
            first.payloadHash(),
            first.previousHash(),
            first.hash());

    assertThat(CanonicalAuditEventSerializer.serialize(first))
        .isEqualTo(CanonicalAuditEventSerializer.serialize(second));
  }

  @Test
  void rawPatientNameAndAddressAreRejectedAndCannotReachCanonicalSerialization() {
    assertThatThrownBy(
            () ->
                AuditPayload.of(
                    Map.of("patientName", "Alice Patient", "address", "1 Example Street")))
        .isInstanceOf(IllegalArgumentException.class);

    final String serialized =
        CanonicalAuditEventSerializer.serialize(event(AuditPayload.of(Map.of("entityCount", "1"))));
    assertThat(serialized)
        .doesNotContain("Alice Patient", "Example Street", "patientName", "address");
  }

  @Test
  void concurrentAppendProducesAnIntactChain() throws Exception {
    final InMemoryAuditEventPublisher publisher = new InMemoryAuditEventPublisher();
    final int count = 100;
    final CountDownLatch ready = new CountDownLatch(count);
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(count);
    final List<Future<AuditEvent>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < count; index++) {
        final int eventNumber = index;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return publisher.publish(
                      event(
                          AuditPayload.of(
                              Map.of("entityCount", Integer.toString(eventNumber + 1)))));
                }));
      }
      ready.await();
      start.countDown();
      for (final Future<AuditEvent> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(publisher.events()).hasSize(count);
    assertThat(publisher.verify().valid()).isTrue();
    assertThat(publisher.verify().brokenSequence()).isEmpty();
  }

  @Test
  void tamperDetectionIdentifiesTheBrokenSequence() {
    final InMemoryAuditEventPublisher publisher = new InMemoryAuditEventPublisher();
    publisher.publish(event(AuditPayload.of(Map.of("entityCount", "1"))));
    publisher.publish(event(AuditPayload.of(Map.of("entityCount", "2"))));
    final List<AuditEvent> tampered = new ArrayList<>(publisher.events());
    final AuditEvent original = tampered.get(1);
    tampered.set(
        1,
        new AuditEvent(
            original.eventId(),
            original.timestamp(),
            original.actor(),
            original.category(),
            original.role(),
            original.action(),
            original.resourceType(),
            original.resourceId(),
            "DENIED",
            original.payload(),
            original.payloadHash(),
            original.previousHash(),
            original.hash()));

    final AuditChainVerificationResult result = new HashChainVerifier().verify(tampered);

    assertThat(result.valid()).isFalse();
    assertThat(result.brokenSequence()).hasValue(2);
    assertThat(result.brokenEventId()).contains(original.eventId());
  }

  @Test
  void publisherExposesExternalAnchorBoundary() {
    final InMemoryAuditEventPublisher publisher = new InMemoryAuditEventPublisher();
    publisher.publish(event(AuditPayload.empty()));
    final List<String> anchored = new ArrayList<>();

    publisher.anchor((hash, sequence) -> anchored.add(hash + ":" + sequence));

    assertThat(anchored).containsExactly(publisher.lastHash() + ":1");
  }

  private static AuditEvent event(final AuditPayload payload) {
    return new AuditEvent(
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z"),
        "service-a",
        "CLINICIAN",
        "READ",
        "CLINICAL_DATA",
        "resource-1",
        "ALLOWED",
        payload);
  }
}
