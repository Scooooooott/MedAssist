package com.medassist.agent.execution;

import com.medassist.domain.Role;
import java.util.Objects;
import java.util.Optional;

/** Request-scoped context for tool work; every async task must clear it on exit. */
public final class AgentThreadContext {
  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

  private AgentThreadContext() {}

  public static Optional<Context> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static <T> T with(
      final ToolInvocationRequest request, final java.util.function.Supplier<T> task) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(task, "task");
    final Context previous = CURRENT.get();
    CURRENT.set(new Context(request.traceId(), request.requestId(), request.role()));
    try {
      return task.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  public record Context(String traceId, String requestId, Role role) {
    public Context {
      if (traceId == null || traceId.isBlank()) {
        throw new IllegalArgumentException("traceId is required");
      }
      if (requestId == null || requestId.isBlank()) {
        throw new IllegalArgumentException("requestId is required");
      }
      Objects.requireNonNull(role, "role");
    }
  }
}
