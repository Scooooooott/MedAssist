package com.medassist.agent.config;

import com.medassist.agent.execution.RetrievalGrpcToolBackend;
import com.medassist.agent.execution.ToolBackend;
import com.medassist.common.tracing.TraceContextClientInterceptor;
import com.medassist.contracts.v1.RetrievalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.retrieval", name = "enabled", havingValue = "true")
public class AgentRetrievalGrpcClientConfiguration {
  @Bean(name = "agentRetrievalGrpcChannel", destroyMethod = "shutdown")
  ManagedChannel agentRetrievalGrpcChannel(final RetrievalProperties properties) {
    return ManagedChannelBuilder.forTarget(properties.endpoint())
        .usePlaintext()
        .intercept(new TraceContextClientInterceptor())
        .build();
  }

  @Bean
  RetrievalServiceGrpc.RetrievalServiceBlockingStub retrievalGrpcStub(
      @Qualifier("agentRetrievalGrpcChannel") final ManagedChannel channel) {
    return RetrievalServiceGrpc.newBlockingStub(channel);
  }

  @Bean
  ToolBackend retrievalToolBackend(
      final RetrievalServiceGrpc.RetrievalServiceBlockingStub stub,
      final RetrievalProperties properties) {
    return new RetrievalGrpcToolBackend(stub, properties.timeout());
  }
}
