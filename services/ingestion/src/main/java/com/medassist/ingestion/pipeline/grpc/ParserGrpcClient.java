package com.medassist.ingestion.pipeline.grpc;

import com.medassist.contracts.v1.DocumentIR;
import com.medassist.contracts.v1.ParseDocumentRequest;
import com.medassist.contracts.v1.ParseDocumentResponse;
import com.medassist.contracts.v1.ParseStatus;
import com.medassist.contracts.v1.ParserServiceGrpc;
import com.medassist.contracts.v1.RequestMetadata;
import com.medassist.ingestion.pipeline.parse.ParserClient;
import com.medassist.ingestion.pipeline.parse.ParserException;
import com.medassist.ingestion.pipeline.parse.ParserPermanentException;
import com.medassist.ingestion.pipeline.parse.ParserRequest;
import com.medassist.ingestion.pipeline.parse.ParserResponse;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Production blocking adapter for the parser service. */
public final class ParserGrpcClient implements ParserClient {
  private final ParserServiceGrpc.ParserServiceBlockingStub stub;

  public ParserGrpcClient(final ParserServiceGrpc.ParserServiceBlockingStub stub) {
    this.stub = Objects.requireNonNull(stub, "stub");
  }

  @Override
  public ParserResponse parse(final ParserRequest request) throws ParserException {
    Objects.requireNonNull(request, "request");
    final ParseDocumentRequest grpcRequest =
        ParseDocumentRequest.newBuilder()
            .setMetadata(RequestMetadata.newBuilder().setRequestId(request.sourceId()).build())
            .setStorageUri(request.storageUri().toString())
            .setMimeType(request.mimeType())
            .setSourceId(request.sourceId())
            .putAllOptions(request.options())
            .build();
    final ParseDocumentResponse response;
    try {
      response =
          stub.withDeadlineAfter(request.timeout().toNanos(), TimeUnit.NANOSECONDS)
              .parseDocument(grpcRequest);
    } catch (final StatusRuntimeException exception) {
      throw GrpcFailureMapper.parser(exception);
    } catch (final RuntimeException exception) {
      throw new ParserPermanentException("parser gRPC client failure", exception);
    }
    if (response == null) {
      throw new ParserPermanentException("parser returned no response");
    }
    if (response.hasError()) {
      throw new ParserPermanentException("parser returned an application error");
    }
    try {
      final com.medassist.ingestion.pipeline.parse.ParseStatus status =
          toDomainStatus(response.getParseStatus());
      final DocumentIR document = response.hasIr() ? response.getIr() : null;
      return new ParserResponse(
          document == null ? null : ParserDocumentMapper.toDomain(document),
          status,
          response.getWarningsList());
    } catch (final ParserPermanentException exception) {
      throw exception;
    } catch (final RuntimeException exception) {
      throw new ParserPermanentException("parser returned an invalid document", exception);
    }
  }

  private static com.medassist.ingestion.pipeline.parse.ParseStatus toDomainStatus(
      final ParseStatus status) throws ParserPermanentException {
    return switch (status) {
      case PARSE_STATUS_SUCCEEDED -> com.medassist.ingestion.pipeline.parse.ParseStatus.SUCCEEDED;
      case PARSE_STATUS_PARTIAL -> com.medassist.ingestion.pipeline.parse.ParseStatus.PARTIAL;
      case PARSE_STATUS_FAILED -> com.medassist.ingestion.pipeline.parse.ParseStatus.FAILED;
      default -> throw new ParserPermanentException("parser returned an unknown status");
    };
  }
}
