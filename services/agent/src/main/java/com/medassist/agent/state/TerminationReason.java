package com.medassist.agent.state;

public enum TerminationReason {
  COMPLETED,
  ABSTAINED,
  MAX_STEPS,
  TIMEOUT,
  DEIDENTIFICATION_FAILED,
  RECOVERY_REJECTED,
  EXECUTION_ERROR
}
