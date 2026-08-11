package com.medassist.retrieval.grpc;

import com.medassist.retrieval.config.RetrievalProperties;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class RetrievalGrpcServerLifecycle implements SmartLifecycle {
  private final RetrievalProperties properties;
  private final RetrievalGrpcService service;
  private Server server;
  private boolean running;

  public RetrievalGrpcServerLifecycle(
      final RetrievalProperties properties, final RetrievalGrpcService service) {
    this.properties = properties;
    this.service = service;
  }

  @Override
  public void start() {
    if (!properties.getGrpc().isEnabled() || running) {
      return;
    }
    try {
      server =
          NettyServerBuilder.forPort(properties.getGrpc().getPort())
              .addService(service)
              .build()
              .start();
      running = true;
    } catch (final IOException exception) {
      throw new IllegalStateException("retrieval gRPC server failed to start", exception);
    }
  }

  @Override
  public void stop() {
    if (server != null) {
      server.shutdown();
      server = null;
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }
}
