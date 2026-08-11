package com.medassist.common.context;

import java.util.Optional;

/** Thread-bound carrier with explicit operations for asynchronous context management. */
public final class ContextCarrier {
  private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

  private ContextCarrier() {}

  /** Captures the context currently bound to this thread. */
  public static Optional<ExecutionContext> capture() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Restores a previously captured, authenticated context. */
  public static void restore(final ExecutionContext context) {
    if (context == null) {
      throw new IllegalArgumentException("execution context must not be null");
    }
    CURRENT.set(context);
  }

  /** Removes all context from the current thread. */
  public static void clear() {
    CURRENT.remove();
  }

  /** Requires an authenticated context, failing closed when none is available. */
  public static ExecutionContext requireCurrent() {
    return capture()
        .orElseThrow(
            () -> new MissingExecutionContextException("authenticated execution context missing"));
  }
}
