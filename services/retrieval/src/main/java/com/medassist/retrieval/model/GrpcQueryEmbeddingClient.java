package com.medassist.retrieval.model;

import com.medassist.contracts.v1.EmbedRequest;
import com.medassist.contracts.v1.EmbeddingInputType;
import com.medassist.contracts.v1.ModelServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GrpcQueryEmbeddingClient implements QueryEmbeddingClient, AutoCloseable {
  private final ManagedChannel channel;
  private final ModelServiceGrpc.ModelServiceBlockingStub stub;

  public GrpcQueryEmbeddingClient(final String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("model service endpoint is required");
    }
    this.channel = ManagedChannelBuilder.forTarget(endpoint).usePlaintext().build();
    this.stub = ModelServiceGrpc.newBlockingStub(channel);
  }

  @Override
  public QueryEmbedding embed(final String query, final String modelName, final String modelVersion) {
    final long started = System.nanoTime();
    final var response =
        stub.withDeadlineAfter(10, TimeUnit.SECONDS)
            .embed(
                EmbedRequest.newBuilder()
                    .setTexts(0, query)
                    .setModelName(modelName)
                    .setInputType(EmbeddingInputType.EMBEDDING_INPUT_TYPE_QUERY)
                    .build());
    if (response.hasError()) {
      throw new IllegalStateException("model service returned an error: " + response.getError().getCode());
    }
    if (!modelVersion.equals(response.getModelVersion())) {
      throw new IllegalStateException(
          "model service returned version " + response.getModelVersion() + " instead of " + modelVersion);
    }
    if (response.getVectorsCount() != 1 || response.getDimension() <= 0) {
      throw new IllegalStateException("model service returned an invalid embedding response");
    }
    return new QueryEmbedding(
        response.getModelName(),
        response.getModelVersion(),
        new ArrayList<>(response.getVectors(0).getValuesList()),
        (System.nanoTime() - started) / 1_000_000L);
  }

  @Override
  public void close() {
    channel.shutdown();
  }
}
