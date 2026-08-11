package com.medassist.agent.checkpoint;

import com.medassist.domain.Role;
import java.util.Objects;
import java.util.Set;

public record RecoveryContext(
    String expectedStateVersion, Role currentRole, Set<String> allowedTools) {
  public RecoveryContext {
    Objects.requireNonNull(expectedStateVersion, "expectedStateVersion");
    Objects.requireNonNull(currentRole, "currentRole");
    Objects.requireNonNull(allowedTools, "allowedTools");
    allowedTools = Set.copyOf(allowedTools);
  }
}
