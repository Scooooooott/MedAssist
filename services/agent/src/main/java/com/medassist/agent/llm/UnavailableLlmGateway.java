package com.medassist.agent.llm;

/** Default fail-closed gateway used until an explicitly configured provider is available. */
public final class UnavailableLlmGateway implements LlmGateway {
  @Override
  public LlmResponse complete(final LlmRequest request) {
    throw LlmGatewayException.unavailable();
  }
}
