package com.medassist.ingestion.pipeline.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.contracts.v1.DetectRequest;
import com.medassist.contracts.v1.DetectResponse;
import com.medassist.contracts.v1.ErrorDetail;
import com.medassist.contracts.v1.PhiEntity;
import com.medassist.ingestion.pipeline.scan.PhiDetectionPermanentException;
import com.medassist.ingestion.pipeline.scan.PhiDetectionRequest;
import com.medassist.ingestion.pipeline.scan.PhiDetectionResponse;
import com.medassist.ingestion.pipeline.scan.PhiDetectionTransientException;
import io.grpc.Status;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PhiDetectionGrpcClientTest {
  private static final Duration TIMEOUT = Duration.ofMillis(275);

  @Test
  void appliesDeadlineAndMapsDetectResponseWithoutRetainingSourceText() throws Exception {
    final DeidServiceGrpc.DeidServiceBlockingStub stub = mockStub();
    final DeidServiceGrpc.DeidServiceBlockingStub timedStub = mockStub();
    when(stub.withDeadlineAfter(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)).thenReturn(timedStub);
    when(timedStub.detect(any()))
        .thenReturn(
            DetectResponse.newBuilder()
                .addEntities(PhiEntity.newBuilder().setEntityType("SYNTHETIC_TYPE").build())
                .build());

    final PhiDetectionResponse result =
        new PhiDetectionGrpcClient(stub)
            .detect(new PhiDetectionRequest("synthetic-sensitive-input", TIMEOUT));

    verify(stub).withDeadlineAfter(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    final ArgumentCaptor<DetectRequest> requestCaptor =
        ArgumentCaptor.forClass(DetectRequest.class);
    verify(timedStub).detect(requestCaptor.capture());
    assertEquals("synthetic-sensitive-input", requestCaptor.getValue().getText());
    assertEquals(java.util.Set.of("SYNTHETIC_TYPE"), result.entityTypes());
  }

  @Test
  void mapsDeadlineExceededToSafeTransientFailure() {
    final String serverDescription = "synthetic-sensitive-input";
    final DeidServiceGrpc.DeidServiceBlockingStub stub = selfTimingStub();
    when(stub.detect(any()))
        .thenThrow(
            Status.DEADLINE_EXCEEDED.withDescription(serverDescription).asRuntimeException());

    final PhiDetectionTransientException exception =
        assertThrows(
            PhiDetectionTransientException.class,
            () -> new PhiDetectionGrpcClient(stub).detect(request()));

    assertEquals("PHI detection gRPC failure: DEADLINE_EXCEEDED", exception.getMessage());
    assertFalse(exception.toString().contains(serverDescription));
    assertNull(exception.getCause());
  }

  @Test
  void mapsNonRetryableGrpcStatusToSafePermanentFailure() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub = selfTimingStub();
    when(stub.detect(any()))
        .thenThrow(
            Status.INVALID_ARGUMENT
                .withDescription("synthetic-sensitive-input")
                .asRuntimeException());

    assertInstanceOf(
        PhiDetectionPermanentException.class,
        assertThrows(
            PhiDetectionPermanentException.class,
            () -> new PhiDetectionGrpcClient(stub).detect(request())));
  }

  @Test
  void failsClosedOnApplicationError() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub = selfTimingStub();
    when(stub.detect(any()))
        .thenReturn(DetectResponse.newBuilder().setError(ErrorDetail.getDefaultInstance()).build());

    final PhiDetectionPermanentException exception =
        assertThrows(
            PhiDetectionPermanentException.class,
            () -> new PhiDetectionGrpcClient(stub).detect(request()));

    assertEquals("PHI detection returned an application error", exception.getMessage());
  }

  @Test
  void failsClosedOnNullOrUnexpectedTransportResponseWithoutLeakingText() {
    final String sourceText = "synthetic-sensitive-input";
    final DeidServiceGrpc.DeidServiceBlockingStub nullStub = selfTimingStub();
    when(nullStub.detect(any())).thenReturn(null);
    final DeidServiceGrpc.DeidServiceBlockingStub failingStub = selfTimingStub();
    when(failingStub.detect(any())).thenThrow(new IllegalStateException(sourceText));

    final PhiDetectionPermanentException nullFailure =
        assertThrows(
            PhiDetectionPermanentException.class,
            () -> new PhiDetectionGrpcClient(nullStub).detect(request()));
    final PhiDetectionPermanentException clientFailure =
        assertThrows(
            PhiDetectionPermanentException.class,
            () -> new PhiDetectionGrpcClient(failingStub).detect(request()));

    assertFalse(nullFailure.toString().contains(sourceText));
    assertFalse(clientFailure.toString().contains(sourceText));
    assertNull(clientFailure.getCause());
  }

  private static PhiDetectionRequest request() {
    return new PhiDetectionRequest("synthetic-sensitive-input", TIMEOUT);
  }

  private static DeidServiceGrpc.DeidServiceBlockingStub selfTimingStub() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub = mockStub();
    when(stub.withDeadlineAfter(eq(TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS))).thenReturn(stub);
    return stub;
  }

  private static DeidServiceGrpc.DeidServiceBlockingStub mockStub() {
    return org.mockito.Mockito.mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
  }
}
