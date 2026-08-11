package com.medassist.agent.execution;

import com.medassist.agent.state.AgentState;

@FunctionalInterface
public interface DraftVerifier {
  VerificationResult verify(GeneratedDraft draft, AgentState state);
}
