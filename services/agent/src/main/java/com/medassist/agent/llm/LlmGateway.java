package com.medassist.agent.llm;

public interface LlmGateway {
  LlmResponse complete(LlmRequest request);
}
