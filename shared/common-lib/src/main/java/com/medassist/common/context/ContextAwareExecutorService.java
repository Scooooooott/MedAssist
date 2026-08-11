package com.medassist.common.context;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** ExecutorService facade that applies the context decorator to every submitted task. */
final class ContextAwareExecutorService extends AbstractExecutorService {
  private final ExecutorService delegate;
  private final TaskDecorator decorator;

  ContextAwareExecutorService(final ExecutorService delegate, final TaskDecorator decorator) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.decorator = Objects.requireNonNull(decorator, "decorator");
  }

  @Override
  public void execute(final Runnable command) {
    delegate.execute(decorator.decorate(command));
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(final long timeout, final TimeUnit unit)
      throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public <T> List<Future<T>> invokeAll(
      final Collection<? extends java.util.concurrent.Callable<T>> tasks)
      throws InterruptedException {
    return super.invokeAll(tasks);
  }
}
