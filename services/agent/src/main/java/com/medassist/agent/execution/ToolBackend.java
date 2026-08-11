package com.medassist.agent.execution;

@FunctionalInterface
public interface ToolBackend {
  ToolBackendResult execute(ToolInvocationRequest request);
}
