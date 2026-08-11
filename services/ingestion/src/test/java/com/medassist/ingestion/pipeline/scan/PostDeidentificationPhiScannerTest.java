package com.medassist.ingestion.pipeline.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PostDeidentificationPhiScannerTest {
  private static final Duration TIMEOUT = Duration.ofMillis(350);

  @Test
  void returnsCleanWhenEveryFragmentHasNoDetectedEntities() throws Exception {
    final List<PhiDetectionRequest> requests = new ArrayList<>();
    final PhiDetectionPort port =
        request -> {
          requests.add(request);
          return new PhiDetectionResponse(Set.of());
        };

    final PostDeidentificationPhiScan result =
        new PostDeidentificationPhiScanner(port).scan(List.of("fragment-a", "fragment-b"), TIMEOUT);

    assertEquals(PhiScanStatus.CLEAN, result.status());
    assertTrue(result.entityTypes().isEmpty());
    assertEquals(2, requests.size());
    assertEquals(TIMEOUT, requests.get(0).timeout());
    assertEquals(TIMEOUT, requests.get(1).timeout());
  }

  @Test
  void returnsSuspectWithAllEntityTypesAfterScanningEveryFragment() throws Exception {
    final AtomicInteger calls = new AtomicInteger();
    final PhiDetectionPort port =
        request ->
            new PhiDetectionResponse(calls.incrementAndGet() == 2 ? Set.of("PERSON") : Set.of());

    final PostDeidentificationPhiScan result =
        new PostDeidentificationPhiScanner(port)
            .scan(List.of("fragment-a", "fragment-b", "fragment-c"), TIMEOUT);

    assertEquals(PhiScanStatus.SUSPECT, result.status());
    assertEquals(Set.of("PERSON"), result.entityTypes());
    assertEquals(3, calls.get());
  }

  @Test
  void failsClosedWhenPortReturnsNull() {
    final String sourceText = "synthetic-sensitive-input";
    final PostDeidentificationPhiScanner scanner =
        new PostDeidentificationPhiScanner(request -> null);

    final PhiDetectionPermanentException exception =
        assertThrows(
            PhiDetectionPermanentException.class, () -> scanner.scan(List.of(sourceText), TIMEOUT));

    assertFalse(exception.toString().contains(sourceText));
    assertNull(exception.getCause());
  }

  @Test
  void failsClosedWithoutLeakingUnexpectedClientFailure() {
    final String sourceText = "synthetic-sensitive-input";
    final PostDeidentificationPhiScanner scanner =
        new PostDeidentificationPhiScanner(
            request -> {
              throw new IllegalStateException(sourceText);
            });

    final PhiDetectionPermanentException exception =
        assertThrows(
            PhiDetectionPermanentException.class, () -> scanner.scan(List.of(sourceText), TIMEOUT));

    assertEquals("PHI detection client failure", exception.getMessage());
    assertFalse(exception.toString().contains(sourceText));
    assertNull(exception.getCause());
  }

  @Test
  void sanitizesDomainFailureRaisedByDetectionPort() {
    final String sourceText = "synthetic-sensitive-input";
    final PostDeidentificationPhiScanner scanner =
        new PostDeidentificationPhiScanner(
            request -> {
              throw new PhiDetectionTransientException(sourceText);
            });

    final PhiDetectionTransientException exception =
        assertThrows(
            PhiDetectionTransientException.class, () -> scanner.scan(List.of(sourceText), TIMEOUT));

    assertEquals("PHI detection failed transiently", exception.getMessage());
    assertFalse(exception.toString().contains(sourceText));
    assertNull(exception.getCause());
  }

  @Test
  void rejectsNullFragmentAndInvalidTimeoutWithoutLeakingOtherFragments() {
    final String sourceText = "synthetic-sensitive-input";
    final PostDeidentificationPhiScanner scanner =
        new PostDeidentificationPhiScanner(request -> new PhiDetectionResponse(Set.of()));

    final PhiDetectionPermanentException nullFragmentFailure =
        assertThrows(
            PhiDetectionPermanentException.class,
            () -> scanner.scan(java.util.Arrays.asList(sourceText, null), TIMEOUT));
    final PhiDetectionPermanentException timeoutFailure =
        assertThrows(
            PhiDetectionPermanentException.class,
            () -> scanner.scan(List.of(sourceText), Duration.ZERO));

    assertFalse(nullFragmentFailure.toString().contains(sourceText));
    assertFalse(timeoutFailure.toString().contains(sourceText));
  }
}
