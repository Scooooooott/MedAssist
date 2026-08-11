package com.medassist.ingestion.pipeline.grpc;

import com.medassist.contracts.v1.EmbedRequest;
import com.medassist.contracts.v1.EmbedResponse;
import com.medassist.contracts.v1.EmbeddingInputType;
import com.medassist.contracts.v1.ModelServiceGrpc;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingRequest;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingResponse;
import com.medassist.ingestion.pipeline.index.EmbeddingInput;
import com.medassist.ingestion.pipeline.index.EmbeddingVector;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Production blocking adapter for ordered batch embedding calls to model-svc. */
public final class ModelEmbeddingGrpcClient implements BatchEmbeddingPort {
  private final ModelServiceGrpc.ModelServiceBlockingStub stub;
  private final Duration timeout;

  public ModelEmbeddingGrpcClient(
      final ModelServiceGrpc.ModelServiceBlockingStub stub, final Duration timeout) {
    this.stub = Objects.requireNonNull(stub, "stub");
    this.timeout = requireTimeout(timeout);
  }

  @Override
  public BatchEmbeddingResponse embed(final BatchEmbeddingRequest request) {
    Objects.requireNonNull(request, "request");
    final String selector = request.model().name() + "@" + request.model().version();
    final EmbedRequest grpcRequest =
        EmbedRequest.newBuilder()
            .addAllTexts(request.inputs().stream().map(EmbeddingInput::text).toList())
            .setModelName(selector)
            .setInputType(EmbeddingInputType.EMBEDDING_INPUT_TYPE_PASSAGE)
            .build();

    final EmbedResponse response;
    try {
      response = stub.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).embed(grpcRequest);
    } catch (final StatusRuntimeException exception) {
      throw mapStatus(exception);
    } catch (final RuntimeException exception) {
      throw new ModelEmbeddingPermanentException("model embedding client failure", exception);
    }
    return toDomain(request, response);
  }

  private static BatchEmbeddingResponse toDomain(
      final BatchEmbeddingRequest request, final EmbedResponse response) {
    if (response == null) {
      throw new ModelEmbeddingPermanentException("model embedding returned no response");
    }
    if (response.hasError()) {
      throw new ModelEmbeddingPermanentException("model embedding returned an application error");
    }
    if (response.getVectorsCount() != request.inputs().size()) {
      throw new ModelEmbeddingPermanentException("model embedding response count mismatch");
    }
    final String expectedName = request.model().name();
    final String expectedVersion = request.model().version();
    if (!expectedName.equals(response.getModelName())
        || !expectedVersion.equals(response.getModelVersion())) {
      throw new ModelEmbeddingPermanentException("model embedding identity mismatch");
    }
    if (response.getDimension() != request.model().dimension()) {
      throw new ModelEmbeddingPermanentException("model embedding dimension mismatch");
    }

    try {
      final List<EmbeddingVector> vectors =
          response.getVectorsList().stream()
              .map(
                  vector ->
                      new EmbeddingVector(
                          toFiniteValues(vector.getValuesList(), request.model().dimension())))
              .toList();
      return new BatchEmbeddingResponse(
          response.getModelName(), response.getModelVersion(), response.getDimension(), vectors);
    } catch (final RuntimeException exception) {
      throw new ModelEmbeddingPermanentException(
          "model embedding returned invalid vectors", exception);
    }
  }

  private static List<Float> toFiniteValues(final List<Float> values, final int expectedDimension) {
    if (values.size() != expectedDimension) {
      throw new IllegalArgumentException("embedding vector dimension mismatch");
    }
    for (final Float value : values) {
      if (value == null || !Float.isFinite(value)) {
        throw new IllegalArgumentException("embedding values must be finite");
      }
    }
    return List.copyOf(values);
  }

  private static ModelEmbeddingException mapStatus(final StatusRuntimeException exception) {
    final Status.Code code = Status.fromThrowable(exception).getCode();
    final String message = "model embedding gRPC failure: " + code.name();
    return GrpcFailureMapper.isTransient(code)
        ? new ModelEmbeddingTransientException(message, exception)
        : new ModelEmbeddingPermanentException(message, exception);
  }

  private static Duration requireTimeout(final Duration value) {
    Objects.requireNonNull(value, "timeout");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    return value;
  }

  /** Base runtime failure for the batch embedding adapter. */
  public abstract static class ModelEmbeddingException extends RuntimeException {
    protected ModelEmbeddingException(final String message) {
      super(message);
    }

    protected ModelEmbeddingException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  /** Failure class that may succeed when the batch is retried. */
  public static final class ModelEmbeddingTransientException extends ModelEmbeddingException {
    public ModelEmbeddingTransientException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  /** Fail-closed failure caused by an invalid request or model response. */
  public static final class ModelEmbeddingPermanentException extends ModelEmbeddingException {
    public ModelEmbeddingPermanentException(final String message) {
      super(message);
    }

    public ModelEmbeddingPermanentException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
