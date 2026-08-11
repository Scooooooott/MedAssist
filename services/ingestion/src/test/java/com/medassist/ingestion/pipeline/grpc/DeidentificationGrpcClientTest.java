package com.medassist.ingestion.pipeline.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.contracts.v1.AnonymizeRequest;
import com.medassist.contracts.v1.AnonymizeResponse;
import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.contracts.v1.PhiEntity;
import com.medassist.ingestion.pipeline.parse.DeidentificationPermanentException;
import com.medassist.ingestion.pipeline.parse.DeidentificationRequest;
import com.medassist.ingestion.pipeline.parse.DeidentificationResponse;
import com.medassist.ingestion.pipeline.parse.DeidentificationTransientException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeidentificationGrpcClientTest {
  @Test
  void appliesDeadlineAndMapsRedactedTextAndEntityCounts() throws Exception {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        org.mockito.Mockito.mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    final DeidServiceGrpc.DeidServiceBlockingStub timedStub =
        org.mockito.Mockito.mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    when(stub.withDeadlineAfter(eq(Duration.ofMillis(400).toNanos()), eq(TimeUnit.NANOSECONDS)))
        .thenReturn(timedStub);
    when(timedStub.anonymize(any()))
        .thenReturn(
            AnonymizeResponse.newBuilder()
                .setText("redacted")
                .setPolicyVersion("policy-v1")
                .addEntities(
                    PhiEntity.newBuilder()
                        .setEntityType("TYPE_A")
                        .setStart(0)
                        .setEnd(4)
                        .setScore(0.9)
                        .setRecognizer("synthetic")
                        .build())
                .build());

    final DeidentificationResponse result =
        new DeidentificationGrpcClient(stub)
            .anonymize(
                new DeidentificationRequest(
                    "synthetic text",
                    "SAFE_HARBOR_REDACT",
                    "document-version-1",
                    Duration.ofMillis(400)));

    verify(stub).withDeadlineAfter(Duration.ofMillis(400).toNanos(), TimeUnit.NANOSECONDS);
    final ArgumentCaptor<AnonymizeRequest> requestCaptor =
        ArgumentCaptor.forClass(AnonymizeRequest.class);
    verify(timedStub).anonymize(requestCaptor.capture());
    assertEquals("document-version-1", requestCaptor.getValue().getOptionsOrThrow("document_key"));
    assertEquals("redacted", result.text());
    assertEquals("policy-v1", result.policyVersion());
    assertEquals("TYPE_A", result.entities().get(0).entityType());
  }

  @Test
  void classifiesDeadlineExceededAsTransient() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        org.mockito.Mockito.mock(DeidServiceGrpc.DeidServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS))).thenReturn(stub);
    when(stub.anonymize(any())).thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

    assertInstanceOf(
        DeidentificationTransientException.class,
        assertThrows(
            DeidentificationTransientException.class,
            () ->
                new DeidentificationGrpcClient(stub)
                    .anonymize(
                        new DeidentificationRequest(
                            "synthetic text",
                            "SAFE_HARBOR_REDACT",
                            "document-version-1",
                            Duration.ofSeconds(1)))));
  }

  @Test
  void rejectsUnsupportedPolicyAsPermanentBeforeTransportCall() {
    final DeidServiceGrpc.DeidServiceBlockingStub stub =
        org.mockito.Mockito.mock(DeidServiceGrpc.DeidServiceBlockingStub.class);

    assertThrows(
        DeidentificationPermanentException.class,
        () ->
            new DeidentificationGrpcClient(stub)
                .anonymize(
                    new DeidentificationRequest(
                        "synthetic text",
                        "UNSUPPORTED",
                        "document-version-1",
                        Duration.ofSeconds(1))));
  }
}
