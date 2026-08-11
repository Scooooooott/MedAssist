package com.medassist.retrieval.rerank;

import com.medassist.contracts.v1.ModelServiceGrpc;
import com.medassist.contracts.v1.RerankCandidate;
import com.medassist.contracts.v1.RerankRequest;
import com.medassist.contracts.v1.RerankResponse;
import com.medassist.retrieval.application.model.RetrievedChunk;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** gRPC adapter for the ModelService rerank RPC. */
public final class GrpcRerankClient implements RerankClient, AutoCloseable {
  private final ManagedChannel channel;
  private final ModelServiceGrpc.ModelServiceBlockingStub stub;

  public GrpcRerankClient(final String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("model service endpoint is required");
    }
    this.channel = ManagedChannelBuilder.forTarget(endpoint).usePlaintext().build();
    this.stub = ModelServiceGrpc.newBlockingStub(channel);
  }

  GrpcRerankClient(
      final ManagedChannel channel, final ModelServiceGrpc.ModelServiceBlockingStub stub) {
    this.channel = Objects.requireNonNull(channel, "channel is required");
    this.stub = Objects.requireNonNull(stub, "stub is required");
  }

  @Override
  public RerankClientResponse rerank(
      final String query,
      final List<RetrievedChunk> candidates,
      final String modelName,
      final Duration timeout) {
    validateRequest(query, candidates, modelName, timeout);
    final RerankRequest request = buildRequest(query, candidates, modelName);
    final RerankResponse response;
    try {
      response = stub.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).rerank(request);
    } catch (final StatusRuntimeException exception) {
      if (exception.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
        throw RerankClientException.timeout("rerank backend deadline exceeded", exception);
      }
      throw RerankClientException.backend("rerank backend request failed", exception);
    } catch (final RuntimeException exception) {
      throw RerankClientException.backend("rerank backend request failed", exception);
    }
    if (response == null) {
      throw RerankClientException.backend("rerank backend returned no response", null);
    }
    if (response.hasError()) {
      throw RerankClientException.backend(
          "rerank backend returned an error: " + response.getError().getCode(), null);
    }
    return new RerankClientResponse(
        response.getResultsList().stream()
            .map(result -> new RerankScore(result.getId(), result.getScore(), result.getRank()))
            .toList(),
        response.getModelName(),
        response.getModelVersion());
  }

  private static RerankRequest buildRequest(
      final String query, final List<RetrievedChunk> candidates, final String modelName) {
    final RerankRequest.Builder request =
        RerankRequest.newBuilder().setQuery(query).setModelName(modelName);
    final Set<String> candidateIds = new HashSet<>();
    for (final RetrievedChunk candidate : candidates) {
      if (candidate == null || candidate.chunkId() == null || candidate.text() == null) {
        throw new IllegalArgumentException("rerank candidates require an id and text");
      }
      if (!candidateIds.add(candidate.chunkId().toString())) {
        throw new IllegalArgumentException("rerank candidate ids must be unique");
      }
      request.addCandidates(
          RerankCandidate.newBuilder()
              .setId(candidate.chunkId().toString())
              .setText(candidate.text())
              .build());
    }
    return request.build();
  }

  private static void validateRequest(
      final String query,
      final List<RetrievedChunk> candidates,
      final String modelName,
      final Duration timeout) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query is required");
    }
    if (candidates == null) {
      throw new IllegalArgumentException("candidates are required");
    }
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("model name is required");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    try {
      timeout.toNanos();
    } catch (final ArithmeticException exception) {
      throw new IllegalArgumentException("timeout is too large", exception);
    }
  }

  @Override
  public void close() {
    channel.shutdown();
  }
}
