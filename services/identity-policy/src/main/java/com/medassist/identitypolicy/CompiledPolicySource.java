package com.medassist.identitypolicy;

@FunctionalInterface
public interface CompiledPolicySource {
  CompiledPolicyArtifact read() throws Exception;
}
