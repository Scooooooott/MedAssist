package com.medassist.common.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContextPropagationTest {
  @AfterEach
  void clearCallerContext() {
    ContextCarrier.clear();
  }

  @Test
  void reusedWorkerDoesNotReusePreviousContext() throws Exception {
    final ExecutorService executor = ExecutorFactory.newFixedThreadPool("context-test", 1);
    try {
      ContextCarrier.restore(context("subject-a"));
      final String first = executor.submit(() -> ContextCarrier.requireCurrent().subject()).get();
      ContextCarrier.restore(context("subject-b"));
      final String second = executor.submit(() -> ContextCarrier.requireCurrent().subject()).get();

      assertEquals("subject-a", first);
      assertEquals("subject-b", second);
      ContextCarrier.clear();
      assertFalse(ContextCarrier.capture().isPresent());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void taskFailureStillClearsWorkerContext() throws Exception {
    final ExecutorService executor = ExecutorFactory.newFixedThreadPool("context-test", 1);
    try {
      ContextCarrier.restore(context("subject-a"));
      final ExecutionException failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  executor
                      .submit(
                          () -> {
                            throw new IllegalStateException("boom");
                          })
                      .get());
      assertEquals("boom", failure.getCause().getMessage());

      ContextCarrier.restore(context("subject-b"));
      assertEquals(
          "subject-b", executor.submit(() -> ContextCarrier.requireCurrent().subject()).get());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void taskFinallyClearsContextEvenWhenTaskRebindsIt() throws Exception {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      ContextCarrier.restore(context("caller"));
      executor
          .submit(
              new ContextTaskDecorator()
                  .decorate(() -> ContextCarrier.restore(context("task-mutated"))))
          .get();

      assertTrue(executor.submit(() -> ContextCarrier.capture().isEmpty()).get());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void residualWorkerContextIsRejectedAndClearedBeforeReuse() throws Exception {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final AtomicReference<ResidualContextException> failure = new AtomicReference<>();
    final AtomicReference<Boolean> cleared = new AtomicReference<>(false);
    try {
      executor
          .submit(
              () -> {
                ContextCarrier.restore(context("stale-worker-context"));
                try {
                  new ContextTaskDecorator().decorate(() -> {}).run();
                } catch (final ResidualContextException exception) {
                  failure.set(exception);
                } finally {
                  cleared.set(ContextCarrier.capture().isEmpty());
                }
              })
          .get();

      assertInstanceOf(ResidualContextException.class, failure.get());
      assertTrue(cleared.get());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void taskWithoutContextFailsClosed() {
    final Runnable decorated = new ContextTaskDecorator().decorate(() -> {});

    assertThrows(MissingExecutionContextException.class, decorated::run);
    assertFalse(ContextCarrier.capture().isPresent());
  }

  @Test
  void propagatesOpenTelemetryContextAcrossAsyncBoundary() throws Exception {
    final ExecutorService executor = ExecutorFactory.newVirtualThreadPerTaskExecutor();
    final io.opentelemetry.context.ContextKey<String> key =
        io.opentelemetry.context.ContextKey.named("test-trace-context");
    ContextCarrier.restore(context("subject-a"));
    try (io.opentelemetry.context.Scope ignored =
        io.opentelemetry.context.Context.current().with(key, "trace-value").makeCurrent()) {
      assertEquals(
          "trace-value",
          executor.submit(() -> io.opentelemetry.context.Context.current().get(key)).get());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void requireCurrentFailsClosedWhenIdentityIsMissing() {
    final MissingExecutionContextException failure =
        assertThrows(MissingExecutionContextException.class, ContextCarrier::requireCurrent);

    assertEquals("authenticated execution context missing", failure.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionContext("", Set.of("reader"), "request", "trace", Map.of()));
  }

  @Test
  void residualEntryIsDetectedAndCleared() {
    ContextCarrier.restore(context("captured"));
    final Runnable decorated = new ContextTaskDecorator().decorate(() -> {});
    ContextCarrier.restore(context("residual"));

    final ResidualContextException failure =
        assertThrows(ResidualContextException.class, decorated::run);

    assertEquals("worker entered with residual execution context", failure.getMessage());
    assertFalse(ContextCarrier.capture().isPresent());
  }

  @Test
  void executionContextCopiesMutableInputs() {
    final Set<String> roles = new java.util.HashSet<>(Set.of("reader"));
    final Map<String, String> obligations = new java.util.HashMap<>(Map.of("purpose", "care"));
    final ExecutionContext context =
        new ExecutionContext("subject", roles, "request", "trace", obligations);

    roles.add("writer");
    obligations.put("purpose", "research");

    assertEquals(Set.of("reader"), context.roles());
    assertEquals(Map.of("purpose", "care"), context.obligations());
    assertInstanceOf(ExecutionContext.class, context);
  }

  private static ExecutionContext context(final String subject) {
    return new ExecutionContext(
        subject, Set.of("reader"), "request-" + subject, "trace-" + subject, Map.of());
  }
}
