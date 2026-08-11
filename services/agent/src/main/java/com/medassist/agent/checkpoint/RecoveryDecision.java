package com.medassist.agent.checkpoint;

import java.util.Objects;

public record RecoveryDecision(boolean allowed, String reason) {
  public RecoveryDecision {
    Objects.requireNonNull(reason, "reason");
  }

  public static RecoveryDecision allow() {
    return new RecoveryDecision(true, "allowed");
  }

  public static RecoveryDecision reject(final String reason) {
    return new RecoveryDecision(false, reason);
  }
}
