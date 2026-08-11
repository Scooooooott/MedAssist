package com.medassist.ingestion.pipeline.grpc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.ingestion.config.IngestionGrpcClientConfiguration;
import com.medassist.ingestion.config.IngestionProperties;
import io.grpc.ManagedChannel;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

class IngestionGrpcClientConfigurationTest {
  @Test
  void channelCanBeReleasedAndBeanDeclaresShutdownLifecycle() throws Exception {
    final IngestionProperties properties = new IngestionProperties();
    final IngestionGrpcClientConfiguration configuration = new IngestionGrpcClientConfiguration();
    final Method method =
        IngestionGrpcClientConfiguration.class.getDeclaredMethod(
            "parserGrpcChannel", IngestionProperties.class);
    method.setAccessible(true);
    final Bean bean = method.getAnnotation(Bean.class);
    final ManagedChannel channel = (ManagedChannel) method.invoke(configuration, properties);

    try {
      assertTrue(bean.destroyMethod().equals("shutdown"));
      assertTrue(channel.shutdown().isShutdown());
    } finally {
      channel.shutdownNow();
    }
  }

  @Test
  void modelChannelCanBeReleasedAndBeanDeclaresShutdownLifecycle() throws Exception {
    final IngestionProperties properties = new IngestionProperties();
    final IngestionGrpcClientConfiguration configuration = new IngestionGrpcClientConfiguration();
    final Method method =
        IngestionGrpcClientConfiguration.class.getDeclaredMethod(
            "modelGrpcChannel", IngestionProperties.class);
    method.setAccessible(true);
    final Bean bean = method.getAnnotation(Bean.class);
    final ManagedChannel channel = (ManagedChannel) method.invoke(configuration, properties);

    try {
      assertTrue(bean.destroyMethod().equals("shutdown"));
      assertTrue(channel.shutdown().isShutdown());
    } finally {
      channel.shutdownNow();
    }
  }
}
