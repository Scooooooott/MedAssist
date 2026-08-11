package com.medassist.ingestion.pipeline.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.contracts.v1.EmbedResponse;
import com.medassist.contracts.v1.EmbeddingInputType;
import com.medassist.contracts.v1.FloatVector;
import com.medassist.contracts.v1.ModelServiceGrpc;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingRequest;
import com.medassist.ingestion.pipeline.index.EmbeddingInput;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ModelEmbeddingGrpcClientTest {
  private static final EmbeddingModel MODEL = new EmbeddingModel("medical-embed", "v1", 3);

  @Test
  void appliesDeadlinePreservesInputOrderAndSendsOnlyEmbeddingText() {
    final ModelServiceGrpc.ModelServiceBlockingStub stub =
        org.mockito.Mockito.mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    final ModelServiceGrpc.ModelServiceBlockingStub timedStub =
        org.mockito.Mockito.mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    when(stub.withDeadlineAfter(eq(Duration.ofMillis(250).toNanos()), eq(TimeUnit.NANOSECONDS)))
        .thenReturn(timedStub);
    when(timedStub.embed(any()))
        .thenReturn(
            EmbedResponse.newBuilder()
                .addVectors(FloatVector.newBuilder().addAllValues(List.of(1.0f, 2.0f, 3.0f)))
                .addVectors(FloatVector.newBuilder().addAllValues(List.of(4.0f, 5.0f, 6.0f)))
                .setModelName("medical-embed")
                .setModelVersion("v1")
                .setDimension(3)
                .build());
    final BatchEmbeddingRequest request =
        new BatchEmbeddingRequest(
            MODEL,
            List.of(
                new EmbeddingInput(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), "first"),
                new EmbeddingInput(
                    UUID.fromString("00000000-0000-0000-0000-000000000002"), "second")));

    final var result = new ModelEmbeddingGrpcClient(stub, Duration.ofMillis(250)).embed(request);

    verify(stub).withDeadlineAfter(Duration.ofMillis(250).toNanos(), TimeUnit.NANOSECONDS);
    final var grpcRequest =
        org.mockito.ArgumentCaptor.forClass(com.medassist.contracts.v1.EmbedRequest.class);
    verify(timedStub).embed(grpcRequest.capture());
    assertEquals(List.of("first", "second"), grpcRequest.getValue().getTextsList());
    assertEquals("medical-embed@v1", grpcRequest.getValue().getModelName());
    assertEquals(
        EmbeddingInputType.EMBEDDING_INPUT_TYPE_PASSAGE, grpcRequest.getValue().getInputType());
    assertFalse(grpcRequest.getValue().hasMetadata());
    assertEquals(List.of(1.0f, 2.0f, 3.0f), result.vectors().get(0).values());
    assertEquals(List.of(4.0f, 5.0f, 6.0f), result.vectors().get(1).values());
  }

  @Test
  void rejectsCountIdentityDimensionAndNonFiniteResponses() {
    assertPermanent(response(1, "medical-embed", "v1", 3), "count");
    assertPermanent(response(2, "other", "v1", 3), "identity");
    assertPermanent(response(2, "medical-embed", "v1", 4), "dimension");
    assertPermanent(
        EmbedResponse.newBuilder()
            .addVectors(FloatVector.newBuilder().addAllValues(List.of(1.0f, 2.0f, 3.0f)))
            .addVectors(FloatVector.newBuilder().addAllValues(List.of(Float.NaN, 5.0f, 6.0f)))
            .setModelName("medical-embed")
            .setModelVersion("v1")
            .setDimension(3)
            .build(),
        "invalid vectors");
  }

  @Test
  void classifiesGrpcFailuresWithoutCopyingStatusDescription() {
    final ModelServiceGrpc.ModelServiceBlockingStub stub =
        org.mockito.Mockito.mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS))).thenReturn(stub);
    when(stub.embed(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("private text")));

    final var exception =
        assertThrows(
            ModelEmbeddingGrpcClient.ModelEmbeddingTransientException.class,
            () -> new ModelEmbeddingGrpcClient(stub, Duration.ofSeconds(1)).embed(request()));
    assertEquals("model embedding gRPC failure: UNAVAILABLE", exception.getMessage());
  }

  @Test
  void classifiesPermanentGrpcAndApplicationErrors() {
    final ModelServiceGrpc.ModelServiceBlockingStub statusStub =
        org.mockito.Mockito.mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    when(statusStub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS)))
        .thenReturn(statusStub);
    when(statusStub.embed(any()))
        .thenThrow(
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("private text")));
    assertInstanceOf(
        ModelEmbeddingGrpcClient.ModelEmbeddingPermanentException.class,
        assertThrows(
            ModelEmbeddingGrpcClient.ModelEmbeddingPermanentException.class,
            () ->
                new ModelEmbeddingGrpcClient(statusStub, Duration.ofSeconds(1)).embed(request())));

    final ModelServiceGrpc.ModelServiceBlockingStub applicationStub =
        org.mockito.Mockito.mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    when(applicationStub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS)))
        .thenReturn(applicationStub);
    when(applicationStub.embed(any()))
        .thenReturn(
            EmbedResponse.newBuilder()
                .setError(com.medassist.contracts.v1.ErrorDetail.newBuilder().setCode("INVALID"))
                .build());
    assertThrows(
        ModelEmbeddingGrpcClient.ModelEmbeddingPermanentException.class,
        () ->
            new ModelEmbeddingGrpcClient(applicationStub, Duration.ofSeconds(1)).embed(request()));
  }

  private static void assertPermanent(final EmbedResponse response, final String ignored) {
    final ModelServiceGrpc.ModelServiceBlockingStub stub =
        org.mockito.Mockito.mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), eq(TimeUnit.NANOSECONDS))).thenReturn(stub);
    when(stub.embed(any())).thenReturn(response);
    assertThrows(
        ModelEmbeddingGrpcClient.ModelEmbeddingPermanentException.class,
        () -> new ModelEmbeddingGrpcClient(stub, Duration.ofSeconds(1)).embed(request()));
  }

  private static EmbedResponse response(
      final int count, final String name, final String version, final int dimension) {
    final EmbedResponse.Builder builder =
        EmbedResponse.newBuilder()
            .setModelName(name)
            .setModelVersion(version)
            .setDimension(dimension);
    for (int index = 0; index < count; index++) {
      builder.addVectors(FloatVector.newBuilder().addAllValues(List.of(1.0f, 2.0f, 3.0f)));
    }
    return builder.build();
  }

  private static BatchEmbeddingRequest request() {
    return new BatchEmbeddingRequest(
        MODEL,
        List.of(
            new EmbeddingInput(UUID.fromString("00000000-0000-0000-0000-000000000001"), "text-1"),
            new EmbeddingInput(UUID.fromString("00000000-0000-0000-0000-000000000002"), "text-2")));
  }
}
