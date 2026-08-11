package com.medassist.ingestion.pipeline.grpc;

import com.medassist.contracts.v1.AnonymizeRequest;
import com.medassist.contracts.v1.AnonymizeResponse;
import com.medassist.contracts.v1.DeidPolicy;
import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.contracts.v1.PhiEntity;
import com.medassist.contracts.v1.RequestMetadata;
import com.medassist.ingestion.pipeline.parse.DeidentificationClient;
import com.medassist.ingestion.pipeline.parse.DeidentificationException;
import com.medassist.ingestion.pipeline.parse.DeidentificationPermanentException;
import com.medassist.ingestion.pipeline.parse.DeidentificationRequest;
import com.medassist.ingestion.pipeline.parse.DeidentificationResponse;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Production blocking adapter for the de-identification service. */
public final class DeidentificationGrpcClient implements DeidentificationClient {
  private final DeidServiceGrpc.DeidServiceBlockingStub stub;

  public DeidentificationGrpcClient(final DeidServiceGrpc.DeidServiceBlockingStub stub) {
    this.stub = Objects.requireNonNull(stub, "stub");
  }

  @Override
  public DeidentificationResponse anonymize(final DeidentificationRequest request)
      throws DeidentificationException {
    Objects.requireNonNull(request, "request");
    final AnonymizeRequest grpcRequest;
    try {
      grpcRequest =
          AnonymizeRequest.newBuilder()
              .setMetadata(RequestMetadata.getDefaultInstance())
              .setText(request.text())
              .setPolicy(toGrpcPolicy(request.policy()))
              .putOptions("document_key", request.documentKey())
              .build();
    } catch (final RuntimeException exception) {
      throw new DeidentificationPermanentException("invalid de-identification policy", exception);
    }

    final AnonymizeResponse response;
    try {
      response =
          stub.withDeadlineAfter(request.timeout().toNanos(), TimeUnit.NANOSECONDS)
              .anonymize(grpcRequest);
    } catch (final StatusRuntimeException exception) {
      throw GrpcFailureMapper.deidentification(exception);
    } catch (final RuntimeException exception) {
      throw new DeidentificationPermanentException(
          "de-identification gRPC client failure", exception);
    }
    if (response == null) {
      throw new DeidentificationPermanentException("de-identification returned no response");
    }
    if (response.hasError()) {
      throw new DeidentificationPermanentException(
          "de-identification returned an application error");
    }
    if (response.getPolicyVersion().isBlank()) {
      throw new DeidentificationPermanentException("de-identification returned no policy version");
    }
    try {
      return new DeidentificationResponse(
          response.getText(),
          response.getEntitiesList().stream().map(DeidentificationGrpcClient::toDomain).toList(),
          response.getPolicyVersion());
    } catch (final RuntimeException exception) {
      throw new DeidentificationPermanentException(
          "de-identification returned invalid entity metadata", exception);
    }
  }

  private static DeidPolicy toGrpcPolicy(final String policy) {
    return switch (policy) {
      case "SAFE_HARBOR_SURROGATE", "DEID_POLICY_SAFE_HARBOR_SURROGATE" ->
          DeidPolicy.DEID_POLICY_SAFE_HARBOR_SURROGATE;
      case "SAFE_HARBOR_REDACT", "DEID_POLICY_SAFE_HARBOR_REDACT" ->
          DeidPolicy.DEID_POLICY_SAFE_HARBOR_REDACT;
      default -> throw new IllegalArgumentException("unsupported policy");
    };
  }

  private static com.medassist.domain.PhiEntity toDomain(final PhiEntity entity) {
    return new com.medassist.domain.PhiEntity(
        entity.getEntityType(),
        entity.getStart(),
        entity.getEnd(),
        entity.getScore(),
        entity.getRecognizer());
  }
}
