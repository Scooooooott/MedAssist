package com.medassist.ingestion.pipeline.grpc;

import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.contracts.v1.DetectRequest;
import com.medassist.contracts.v1.DetectResponse;
import com.medassist.contracts.v1.RequestMetadata;
import com.medassist.ingestion.pipeline.scan.PhiDetectionException;
import com.medassist.ingestion.pipeline.scan.PhiDetectionPermanentException;
import com.medassist.ingestion.pipeline.scan.PhiDetectionPort;
import com.medassist.ingestion.pipeline.scan.PhiDetectionRequest;
import com.medassist.ingestion.pipeline.scan.PhiDetectionResponse;
import com.medassist.ingestion.pipeline.scan.PhiDetectionTransientException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Blocking Detect adapter used for the final pre-index PHI scan. */
public final class PhiDetectionGrpcClient implements PhiDetectionPort {
  private final DeidServiceGrpc.DeidServiceBlockingStub stub;

  public PhiDetectionGrpcClient(final DeidServiceGrpc.DeidServiceBlockingStub stub) {
    this.stub = Objects.requireNonNull(stub, "stub");
  }

  @Override
  public PhiDetectionResponse detect(final PhiDetectionRequest request)
      throws PhiDetectionException {
    if (request == null) {
      throw new PhiDetectionPermanentException("PHI detection request is invalid");
    }

    final DetectRequest grpcRequest =
        DetectRequest.newBuilder()
            .setMetadata(RequestMetadata.getDefaultInstance())
            .setText(request.text())
            .build();
    final DetectResponse response;
    try {
      response =
          stub.withDeadlineAfter(request.timeout().toNanos(), TimeUnit.NANOSECONDS)
              .detect(grpcRequest);
    } catch (final StatusRuntimeException exception) {
      throw mapTransportFailure(exception);
    } catch (final RuntimeException exception) {
      throw new PhiDetectionPermanentException("PHI detection gRPC client failure");
    }

    if (response == null) {
      throw new PhiDetectionPermanentException("PHI detection returned no response");
    }
    if (response.hasError()) {
      throw new PhiDetectionPermanentException("PHI detection returned an application error");
    }
    return new PhiDetectionResponse(
        response.getEntitiesList().stream()
            .map(com.medassist.contracts.v1.PhiEntity::getEntityType)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }

  private static PhiDetectionException mapTransportFailure(final StatusRuntimeException exception) {
    final Status.Code code = Status.fromThrowable(exception).getCode();
    final String message = "PHI detection gRPC failure: " + code.name();
    return GrpcFailureMapper.isTransient(code)
        ? new PhiDetectionTransientException(message)
        : new PhiDetectionPermanentException(message);
  }
}
