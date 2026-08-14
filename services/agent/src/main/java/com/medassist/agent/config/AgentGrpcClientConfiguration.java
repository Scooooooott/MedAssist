package com.medassist.agent.config;

import com.medassist.agent.application.GrpcQueryDeidentifier;
import com.medassist.agent.application.QueryDeidentifier;
import com.medassist.common.resilience.ResilienceExecutor;
import com.medassist.common.tracing.TraceContextClientInterceptor;
import com.medassist.contracts.v1.DeidServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DeidProperties.class)
@ConditionalOnProperty(prefix = "agent.deid", name = "enabled", havingValue = "true")
public class AgentGrpcClientConfiguration {
  @Bean(name = "agentDeidGrpcChannel", destroyMethod = "shutdown")
  ManagedChannel agentDeidGrpcChannel(final DeidProperties properties) {
    return ManagedChannelBuilder.forTarget(properties.endpoint())
        .usePlaintext()
        .intercept(new TraceContextClientInterceptor())
        .build();
  }

  @Bean
  DeidServiceGrpc.DeidServiceBlockingStub agentDeidGrpcStub(
      @Qualifier("agentDeidGrpcChannel") final ManagedChannel channel) {
    return DeidServiceGrpc.newBlockingStub(channel);
  }

  @Bean
  QueryDeidentifier grpcQueryDeidentifier(
      final DeidServiceGrpc.DeidServiceBlockingStub stub,
      final DeidProperties properties,
      final ResilienceExecutor resilienceExecutor) {
    return new GrpcQueryDeidentifier(stub, properties, resilienceExecutor);
  }
}
