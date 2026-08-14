package com.medassist.common.context;

import java.util.Objects;
import java.util.Optional;

/** Default fail-closed decorator for tasks executed on pooled threads. */
public final class ContextTaskDecorator implements TaskDecorator {
  @Override
  public Runnable decorate(final Runnable task) {
    Objects.requireNonNull(task, "task");
    final Optional<ExecutionContext> captured = ContextCarrier.capture();
    final io.opentelemetry.context.Context traceContext =
        io.opentelemetry.context.Context.current();
    return () -> runWithContext(task, captured, traceContext);
  }

  private static void runWithContext(
      final Runnable task,
      final Optional<ExecutionContext> capturedContext,
      final io.opentelemetry.context.Context traceContext) {
    try (io.opentelemetry.context.Scope ignored = traceContext.makeCurrent()) {
      if (ContextCarrier.capture().isPresent()) {
        throw new ResidualContextException("worker entered with residual execution context");
      }
      final ExecutionContext context =
          capturedContext.orElseThrow(
              () ->
                  new MissingExecutionContextException(
                      "task submitted without authenticated execution context"));
      ContextCarrier.restore(context);
      task.run();
    } finally {
      ContextCarrier.clear();
    }
  }
}
