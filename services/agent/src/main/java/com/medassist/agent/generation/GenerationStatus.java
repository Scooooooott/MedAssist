package com.medassist.agent.generation;

public enum GenerationStatus {
  CREATED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED,
  EXPIRED;

  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
  }
}
