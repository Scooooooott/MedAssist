package com.medassist.common.context;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates context-aware executors for application asynchronous work. */
public final class ExecutorFactory {
  private ExecutorFactory() {}

  public static ExecutorService newFixedThreadPool(
      final String threadNamePrefix, final int parallelism) {
    requireParallelism(parallelism);
    return newContextAwareExecutor(
        Executors.newFixedThreadPool(parallelism, namedThreadFactory(threadNamePrefix)));
  }

  public static ExecutorService newSingleThreadExecutor(final String threadNamePrefix) {
    return newContextAwareExecutor(
        Executors.newSingleThreadExecutor(namedThreadFactory(threadNamePrefix)));
  }

  public static ScheduledExecutorService newSingleThreadScheduledExecutor(
      final String threadNamePrefix) {
    return new ContextAwareScheduledExecutorService(
        Executors.newSingleThreadScheduledExecutor(namedThreadFactory(threadNamePrefix)),
        new ContextTaskDecorator());
  }

  /** Creates one virtual thread per task; concurrency must be bounded at each downstream call. */
  public static ExecutorService newVirtualThreadPerTaskExecutor() {
    return newContextAwareExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  private static ExecutorService newContextAwareExecutor(final ExecutorService delegate) {
    return new ContextAwareExecutorService(delegate, new ContextTaskDecorator());
  }

  private static ThreadFactory namedThreadFactory(final String prefix) {
    Objects.requireNonNull(prefix, "threadNamePrefix");
    if (prefix.isBlank()) {
      throw new IllegalArgumentException("threadNamePrefix must not be blank");
    }
    final AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      final Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static void requireParallelism(final int parallelism) {
    if (parallelism <= 0) {
      throw new IllegalArgumentException("parallelism must be positive");
    }
  }
}
