package com.medassist.common.resilience;

/** Wraps a checked downstream failure without changing its retry classification cause. */
public final class ResilienceExecutionException extends RuntimeException {
  private final ResilienceComponent component;

  public ResilienceExecutionException(
      final ResilienceComponent component, final String message, final Throwable cause) {
    super(message, cause);
    this.component = component;
  }

  public ResilienceComponent component() {
    return component;
  }
}
