package com.medassist.agent.execution;

@FunctionalInterface
public interface DraftGenerator {
  GeneratedDraft generate(AgentGenerationContext context);
}
