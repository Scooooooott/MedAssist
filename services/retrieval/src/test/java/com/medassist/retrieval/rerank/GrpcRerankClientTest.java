package com.medassist.retrieval.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.contracts.v1.ModelServiceGrpc;
import com.medassist.contracts.v1.RerankResponse;
import com.medassist.retrieval.application.model.RetrievedChunk;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GrpcRerankClientTest {
  @Test
  void buildsTextOnlyCandidateRequestAndUsesConfiguredDeadline() {
    final ManagedChannel channel = mock(ManagedChannel.class);
    final ModelServiceGrpc.ModelServiceBlockingStub stub =
        mock(ModelServiceGrpc.ModelServiceBlockingStub.class);
    final RetrievedChunk chunk = chunk();
    final RerankResponse response =
        RerankResponse.newBuilder()
            .setModelName("online-reranker")
            .setModelVersion("v1")
            .addResults(
                com.medassist.contracts.v1.RerankResult.newBuilder()
                    .setId(chunk.chunkId().toString())
                    .setScore(0.9)
                    .setRank(1)
                    .build())
            .build();
    when(stub.withDeadlineAfter(250_000_000L, TimeUnit.NANOSECONDS)).thenReturn(stub);
    when(stub.rerank(any())).thenReturn(response);
    final GrpcRerankClient client = new GrpcRerankClient(channel, stub);

    final RerankClientResponse result =
        client.rerank(
            "de-identified query", List.of(chunk), "online-reranker", Duration.ofMillis(250));

    final ArgumentCaptor<com.medassist.contracts.v1.RerankRequest> request =
        ArgumentCaptor.forClass(com.medassist.contracts.v1.RerankRequest.class);
    verify(stub).withDeadlineAfter(250_000_000L, TimeUnit.NANOSECONDS);
    verify(stub).rerank(request.capture());
    assertThat(request.getValue().getQuery()).isEqualTo("de-identified query");
    assertThat(request.getValue().getModelName()).isEqualTo("online-reranker");
    assertThat(request.getValue().getCandidatesCount()).isEqualTo(1);
    assertThat(request.getValue().getCandidates(0).getId()).isEqualTo(chunk.chunkId().toString());
    assertThat(request.getValue().getCandidates(0).getText()).isEqualTo(chunk.text());
    assertThat(request.getValue().getCandidates(0).getMetadataMap()).isEmpty();
    assertThat(result.modelName()).isEqualTo("online-reranker");
    assertThat(result.results())
        .containsExactly(new RerankScore(chunk.chunkId().toString(), 0.9, 1));
  }

  private static RetrievedChunk chunk() {
    return new RetrievedChunk(
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        UUID.randomUUID(),
        1,
        "section",
        "source faithful text",
        3,
        0,
        20,
        0.5,
        "HYBRID",
        "COSINE",
        "GUIDELINE",
        "publisher",
        "title",
        "v1",
        LocalDate.of(2026, 1, 1),
        Map.of("sensitive", "must-not-send"));
  }
}
