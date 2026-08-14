package com.medassist.agent.llm.routing;

import com.medassist.agent.llm.LlmRequest;
import com.medassist.agent.llm.LlmResponse;

public interface LlmProviderAdapter {
  LlmProviderDefinition definition();

  LlmResponse complete(LlmRequest request);
}
