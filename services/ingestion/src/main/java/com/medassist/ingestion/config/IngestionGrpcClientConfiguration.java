package com.medassist.ingestion.config;

import com.medassist.common.tracing.TraceContextClientInterceptor;
import com.medassist.contracts.v1.DeidServiceGrpc;
import com.medassist.contracts.v1.ModelServiceGrpc;
import com.medassist.contracts.v1.ParserServiceGrpc;
import com.medassist.ingestion.pipeline.grpc.DeidentificationGrpcClient;
import com.medassist.ingestion.pipeline.grpc.ModelEmbeddingGrpcClient;
import com.medassist.ingestion.pipeline.grpc.ParserGrpcClient;
import com.medassist.ingestion.pipeline.grpc.PhiDetectionGrpcClient;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.parse.DeidentificationClient;
import com.medassist.ingestion.pipeline.parse.ParserClient;
import com.medassist.ingestion.pipeline.scan.PhiDetectionPort;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires sidecar clients to managed channels owned by the Spring context. */
@Configuration
public class IngestionGrpcClientConfiguration {
  @Bean(name = "parserGrpcChannel", destroyMethod = "shutdown")
  ManagedChannel parserGrpcChannel(final IngestionProperties properties) {
    return channel(properties.getServices().getParserEndpoint());
  }

  @Bean(name = "deidGrpcChannel", destroyMethod = "shutdown")
  ManagedChannel deidGrpcChannel(final IngestionProperties properties) {
    return channel(properties.getServices().getDeidEndpoint());
  }

  @Bean(name = "modelGrpcChannel", destroyMethod = "shutdown")
  ManagedChannel modelGrpcChannel(final IngestionProperties properties) {
    return channel(properties.getServices().getModelEndpoint());
  }

  @Bean
  ParserServiceGrpc.ParserServiceBlockingStub parserGrpcStub(
      @Qualifier("parserGrpcChannel") final ManagedChannel channel) {
    return ParserServiceGrpc.newBlockingStub(channel);
  }

  @Bean
  DeidServiceGrpc.DeidServiceBlockingStub deidGrpcStub(
      @Qualifier("deidGrpcChannel") final ManagedChannel channel) {
    return DeidServiceGrpc.newBlockingStub(channel);
  }

  @Bean
  ModelServiceGrpc.ModelServiceBlockingStub modelGrpcStub(
      @Qualifier("modelGrpcChannel") final ManagedChannel channel) {
    return ModelServiceGrpc.newBlockingStub(channel);
  }

  @Bean
  ParserClient parserClient(final ParserServiceGrpc.ParserServiceBlockingStub stub) {
    return new ParserGrpcClient(stub);
  }

  @Bean
  DeidentificationClient deidentificationClient(
      final DeidServiceGrpc.DeidServiceBlockingStub stub) {
    return new DeidentificationGrpcClient(stub);
  }

  @Bean
  PhiDetectionPort phiDetectionPort(final DeidServiceGrpc.DeidServiceBlockingStub stub) {
    return new PhiDetectionGrpcClient(stub);
  }

  @Bean
  PostDeidentificationPhiScanner postDeidentificationPhiScanner(
      final PhiDetectionPort detectionPort) {
    return new PostDeidentificationPhiScanner(detectionPort);
  }

  @Bean
  BatchEmbeddingPort batchEmbeddingPort(
      final ModelServiceGrpc.ModelServiceBlockingStub stub, final IngestionProperties properties) {
    return new ModelEmbeddingGrpcClient(stub, properties.getModelTimeout());
  }

  private static ManagedChannel channel(final String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("gRPC endpoint must not be blank");
    }
    return ManagedChannelBuilder.forTarget(endpoint)
        .usePlaintext()
        .intercept(new TraceContextClientInterceptor())
        .build();
  }
}
